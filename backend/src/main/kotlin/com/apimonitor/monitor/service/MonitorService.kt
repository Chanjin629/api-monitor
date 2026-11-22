package com.apimonitor.monitor.service

import com.apimonitor.check.repository.CheckResultRepository
import com.apimonitor.global.crypto.AesGcmEncryptor
import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import com.apimonitor.global.response.ErrorDetail
import com.apimonitor.global.ssrf.UrlSafetyValidator
import com.apimonitor.member.dto.MemberResponse
import com.apimonitor.member.repository.MemberRepository
import com.apimonitor.monitor.domain.Monitor
import com.apimonitor.monitor.domain.MonitorHttpMethod
import com.apimonitor.monitor.domain.MonitorState
import com.apimonitor.monitor.domain.MonitorStatus
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.dto.HeaderRequest
import com.apimonitor.monitor.dto.MonitorListResponse
import com.apimonitor.monitor.dto.MonitorResponse
import com.apimonitor.monitor.dto.UpdateMonitorRequest
import com.apimonitor.monitor.repository.MonitorRepository
import com.apimonitor.monitor.repository.MonitorStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

@Service
@Transactional(readOnly = true)
class MonitorService(
    private val monitorRepository: MonitorRepository,
    private val monitorStateRepository: MonitorStateRepository,
    private val memberRepository: MemberRepository,
    private val checkResultRepository: CheckResultRepository,
    private val urlSafetyValidator: UrlSafetyValidator,
    private val encryptor: AesGcmEncryptor,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun getMonitors(memberId: Long): MonitorListResponse {
        val monitors = monitorRepository.findAllByMemberIdOrderByIdAsc(memberId)
        val ids = monitors.map { it.id!! }
        val states = monitorStateRepository.findAllById(ids).associateBy { it.monitorId }
        // 모든 모니터의 가동률을 쿼리 한 번으로 구한다.
        val uptimes = checkResultRepository.uptimeSince(ids, clock.instant().minus(UPTIME_WINDOW))
        val items = monitors.map { toResponse(it, states.getValue(it.id!!), uptimes[it.id!!]) }
        return MonitorListResponse(items = items, nextCursor = null)
    }

    fun getMonitor(memberId: Long, monitorId: Long): MonitorResponse {
        val monitor = findOwned(memberId, monitorId)
        return toResponse(monitor, findState(monitor))
    }

    @Transactional
    fun create(memberId: Long, request: CreateMonitorRequest): MonitorResponse {
        val url = request.url!!.trim()
        val intervalMinutes = validateInterval(request.intervalMinutes!!)
        val expectedStatusCodes = validateStatusCodes(request.expectedStatusCodes)
        val headers = validateHeaders(request.headers, rejectMasked = false)
        // DNS 조회는 최대 3초가 걸릴 수 있으므로 잠금을 잡기 전에 한다.
        urlSafetyValidator.validate(url)

        // 회원 행을 잠가 같은 회원의 등록 요청을 한 번에 하나씩 처리한다. 동시에 보내도 제한을 넘지 않는다.
        memberRepository.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.AUTH_TOKEN_INVALID)
        if (monitorRepository.countByMemberId(memberId) >= MemberResponse.MONITOR_LIMIT) {
            throw BusinessException(ErrorCode.MONITOR_LIMIT_EXCEEDED)
        }

        val now = clock.instant()
        val monitor = monitorRepository.save(
            Monitor(
                memberId = memberId,
                name = request.name!!.trim(),
                url = url,
                httpMethod = request.method ?: MonitorHttpMethod.GET,
                intervalMinutes = intervalMinutes,
                timeoutSeconds = request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS,
                expectedStatusCodes = expectedStatusCodes,
                requestHeadersEncrypted = encryptHeaders(headers),
                createdAt = now,
            ),
        )
        val state = monitorStateRepository.save(
            MonitorState(monitorId = monitor.id!!, status = MonitorStatus.UNKNOWN, nextCheckAt = firstCheckAt(now)),
        )
        return toResponse(monitor, state)
    }

    @Transactional
    fun update(memberId: Long, monitorId: Long, request: UpdateMonitorRequest): MonitorResponse {
        val intervalMinutes = request.intervalMinutes?.let { validateInterval(it) }
        val expectedStatusCodes = request.expectedStatusCodes?.let { validateStatusCodes(it) }
        val headers = request.headers?.let { validateHeaders(it, rejectMasked = true) }
        val newUrl = request.url?.trim()

        val monitor = findOwned(memberId, monitorId)
        val state = findState(monitor)
        val urlChanged = newUrl != null && newUrl != monitor.url
        if (urlChanged) {
            urlSafetyValidator.validate(newUrl!!)
            monitor.changeUrl(newUrl)
        }

        request.name?.let { monitor.rename(it.trim()) }
        request.method?.let { monitor.changeHttpMethod(it) }
        intervalMinutes?.let { monitor.changeInterval(it) }
        request.timeoutSeconds?.let { monitor.changeTimeout(it) }
        if (request.expectedStatusCodes != null) monitor.changeExpectedStatusCodes(expectedStatusCodes)
        if (headers != null) monitor.changeRequestHeaders(encryptHeaders(headers))

        val now = clock.instant()
        monitor.touch(now)
        // 다른 주소의 연속 실패·성공 기록은 의미가 없으므로 처음부터 다시 판정한다.
        if (urlChanged) {
            if (state.status == MonitorStatus.PAUSED) {
                state.resetCounters()
            } else {
                state.restart(hasOpenIncident(monitorId), firstCheckAt(now))
            }
        }
        return toResponse(monitor, state)
    }

    /** 상태, 집계, 장애 이력은 DB의 ON DELETE CASCADE로 함께 지워진다. 검사 원본 기록은 14일 보관 후 파티션째 삭제된다. */
    @Transactional
    fun delete(memberId: Long, monitorId: Long) {
        monitorRepository.delete(findOwned(memberId, monitorId))
    }

    @Transactional
    fun pause(memberId: Long, monitorId: Long): MonitorResponse {
        val monitor = findOwned(memberId, monitorId)
        val state = findState(monitor)
        if (state.status == MonitorStatus.PAUSED) throw BusinessException(ErrorCode.MONITOR_ALREADY_PAUSED)
        state.pause()
        return toResponse(monitor, state)
    }

    @Transactional
    fun resume(memberId: Long, monitorId: Long): MonitorResponse {
        val monitor = findOwned(memberId, monitorId)
        val state = findState(monitor)
        if (state.status != MonitorStatus.PAUSED) throw BusinessException(ErrorCode.MONITOR_NOT_PAUSED)
        state.restart(hasOpenIncident(monitorId), firstCheckAt(clock.instant()))
        return toResponse(monitor, state)
    }

    /** 다른 회원의 모니터도 "없음"으로 응답해, 그 ID가 존재하는지 드러내지 않는다. */
    private fun findOwned(memberId: Long, monitorId: Long): Monitor =
        monitorRepository.findByIdAndMemberId(monitorId, memberId)
            ?: throw BusinessException(ErrorCode.MONITOR_NOT_FOUND)

    private fun findState(monitor: Monitor): MonitorState =
        monitorStateRepository.findById(monitor.id!!).orElseThrow {
            IllegalStateException("모니터 상태가 없습니다. monitorId=${monitor.id}")
        }

    private fun hasOpenIncident(monitorId: Long): Boolean = monitorStateRepository.countOpenIncidents(monitorId) > 0

    /**
     * 첫 검사는 0~30초 뒤 무작위 시각에 한다.
     * 여러 모니터를 같은 시각에 등록해도 검사가 한 순간에 몰리지 않게 흩어 놓는다.
     */
    private fun firstCheckAt(now: Instant): Instant =
        now.plusMillis(Random.nextLong(0, FIRST_CHECK_JITTER.toMillis() + 1))

    private fun toResponse(monitor: Monitor, state: MonitorState): MonitorResponse =
        toResponse(monitor, state, checkResultRepository.uptimeSince(listOf(monitor.id!!), clock.instant().minus(UPTIME_WINDOW))[monitor.id!!])

    private fun toResponse(monitor: Monitor, state: MonitorState, uptime24h: Double?): MonitorResponse =
        MonitorResponse.of(monitor, state, decryptHeaders(monitor.requestHeadersEncrypted).map { it.name }, uptime24h)

    private fun validateInterval(intervalMinutes: Int): Int {
        if (intervalMinutes !in ALLOWED_INTERVALS) {
            throw validationFailed("intervalMinutes", "1, 5, 10, 30, 60 중 하나여야 합니다.")
        }
        return intervalMinutes
    }

    /** 저장 형식은 "200,301". 비어 있으면 null(2xx 전체)로 저장한다. */
    private fun validateStatusCodes(codes: List<Int>?): String? {
        if (codes.isNullOrEmpty()) return null
        if (codes.any { it !in 100..599 }) {
            throw validationFailed("expectedStatusCodes", "100~599 사이여야 합니다.")
        }
        return codes.distinct().sorted().joinToString(",")
    }

    private fun validateHeaders(headers: List<HeaderRequest>?, rejectMasked: Boolean): List<StoredHeader> {
        if (headers.isNullOrEmpty()) return emptyList()
        headers.forEachIndexed { index, header ->
            if (header.name!!.lowercase() in FORBIDDEN_HEADERS) {
                throw BusinessException(
                    ErrorCode.HEADER_NOT_ALLOWED,
                    details = listOf(ErrorDetail("headers[$index].name", "${header.name} 헤더는 지정할 수 없습니다.")),
                )
            }
            // 화면에 가려서 보여 준 값을 그대로 다시 보내면 실제 토큰이 "********"로 덮어써진다.
            if (rejectMasked && header.value == MonitorResponse.MASKED_VALUE) {
                throw validationFailed("headers[$index].value", "가려진 값은 저장할 수 없습니다. 값을 다시 입력해 주세요.")
            }
        }
        return headers.map { StoredHeader(it.name!!, it.value!!) }
    }

    private fun encryptHeaders(headers: List<StoredHeader>): String? =
        if (headers.isEmpty()) null else encryptor.encrypt(jsonMapper.writeValueAsString(headers))

    private fun decryptHeaders(encrypted: String?): List<StoredHeader> =
        if (encrypted == null) emptyList() else jsonMapper.readValue(encryptor.decrypt(encrypted))

    private fun validationFailed(field: String, reason: String) =
        BusinessException(ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail(field, reason)))

    /** 암호화해 저장하는 헤더 한 개. 검사 실행부도 같은 형식으로 복호화한다. */
    data class StoredHeader(val name: String, val value: String)

    companion object {
        private val ALLOWED_INTERVALS = setOf(1, 5, 10, 30, 60)
        private val UPTIME_WINDOW: Duration = Duration.ofHours(24)
        private const val DEFAULT_TIMEOUT_SECONDS = 10
        private val FIRST_CHECK_JITTER: Duration = Duration.ofSeconds(30)

        // 요청 형식을 깨뜨리거나 다른 서버로 보내게 만들 수 있는 헤더
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")
    }
}
