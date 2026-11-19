package com.apimonitor.check.scheduler

import com.apimonitor.check.dto.CheckCommand
import com.apimonitor.check.executor.CheckExecutor
import com.apimonitor.check.repository.CheckTargetRepository
import com.apimonitor.check.repository.DueTarget
import com.apimonitor.global.crypto.AesGcmEncryptor
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 검사 설정.
 * @property dispatcherEnabled false면 주기 실행을 하지 않는다. 테스트에서는 꺼 두고 [CheckDispatcher.dispatch]를 직접 부른다.
 * @property batchSize 한 번에 선점하는 최대 모니터 수
 * @property maxConcurrency 동시에 실행하는 최대 검사 수 (N-P2)
 */
@ConfigurationProperties("check")
data class CheckProperties(
    val dispatcherEnabled: Boolean = true,
    val batchSize: Int = 200,
    val maxConcurrency: Int = 200,
)

/**
 * 검사 시각이 지난 모니터를 찾아 실행기에 넘긴다. (F-C1)
 * 모니터마다 타이머를 만들지 않고, 짧은 주기의 디스패처 하나가 DB의 next_check_at을 보고 판단한다.
 */
@Component
class CheckDispatcher(
    private val checkTargetRepository: CheckTargetRepository,
    private val checkExecutor: CheckExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val encryptor: AesGcmEncryptor,
    private val jsonMapper: JsonMapper,
    private val properties: CheckProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 이전 실행이 끝난 뒤 5초를 쉰다. 실행이 길어져도 겹치지 않는다.
    @Scheduled(fixedDelayString = "\${check.dispatch-interval-ms:5000}", initialDelayString = "\${check.dispatch-interval-ms:5000}")
    fun scheduledDispatch() {
        if (!properties.dispatcherEnabled) return
        try {
            dispatch()
        } catch (e: Exception) {
            // DB 일시 장애 등. 다음 주기에 다시 시도한다.
            log.error("Check dispatch failed", e)
        }
    }

    /** 검사 대상을 선점해 실행기에 넘기고, 넘긴 개수를 돌려준다. */
    fun dispatch(): Int {
        // 실행기에 빈자리가 있는 만큼만 가져온다. 가져와 놓고 오래 기다리면 검사가 예정보다 늦어진다.
        val limit = minOf(properties.batchSize, checkExecutor.availableSlots())
        if (limit <= 0) return 0

        val now = clock.instant()
        val targets = transactionTemplate.execute {
            checkTargetRepository.findDueForUpdate(now, limit).also { claimed ->
                checkTargetRepository.moveNextCheckAt(claimed.associate { it.monitorId to nextCheckAt(it, now) })
            }
        }.orEmpty()

        targets.forEach { target ->
            val command = try {
                toCommand(target)
            } catch (e: Exception) {
                // 암호화 키가 바뀌어 헤더를 복호화할 수 없는 경우 등. 이 모니터만 건너뛰고 나머지는 계속한다.
                log.error("Failed to prepare check monitorId={}", target.monitorId, e)
                return@forEach
            }
            checkExecutor.execute(command)
        }
        return targets.size
    }

    /**
     * 다음 정기 검사 시각. 실패 직후 30초 재검사 같은 판정에 따른 조정은 12단계에서 검사 결과를 처리할 때 한다.
     */
    private fun nextCheckAt(target: DueTarget, now: Instant): Instant =
        now.plus(Duration.ofMinutes(target.intervalMinutes.toLong()))

    private fun toCommand(target: DueTarget): CheckCommand = CheckCommand(
        monitorId = target.monitorId,
        url = target.url,
        method = target.httpMethod,
        timeoutSeconds = target.timeoutSeconds,
        expectedStatusCodes = target.expectedStatusCodes?.split(',')?.map { it.trim().toInt() }.orEmpty(),
        headers = target.requestHeadersEncrypted
            ?.let { jsonMapper.readValue<List<CheckCommand.Header>>(encryptor.decrypt(it)) }
            .orEmpty(),
    )
}
