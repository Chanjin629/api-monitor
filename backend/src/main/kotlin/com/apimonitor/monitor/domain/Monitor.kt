package com.apimonitor.monitor.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 모니터 설정. 사용자가 바꾸는 값만 담고, 검사 결과에 따라 바뀌는 값은 [MonitorState]에 둔다.
 * 요청 헤더는 인증 토큰이 들어 있을 수 있으므로 암호화한 문자열로만 저장한다.
 */
@Entity
@Table(name = "monitors")
class Monitor(
    val memberId: Long,
    name: String,
    url: String,
    httpMethod: MonitorHttpMethod,
    intervalMinutes: Int,
    timeoutSeconds: Int,
    expectedStatusCodes: String?,
    requestHeadersEncrypted: String?,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var name: String = name
        protected set

    var url: String = url
        protected set

    @Enumerated(EnumType.STRING)
    var httpMethod: MonitorHttpMethod = httpMethod
        protected set

    @Column(columnDefinition = "SMALLINT")
    var intervalMinutes: Int = intervalMinutes
        protected set

    @Column(columnDefinition = "SMALLINT")
    var timeoutSeconds: Int = timeoutSeconds
        protected set

    /** 쉼표로 구분한 상태 코드 (예: "200,301"). null이면 2xx 전체 */
    var expectedStatusCodes: String? = expectedStatusCodes
        protected set

    @Column(columnDefinition = "TEXT")
    var requestHeadersEncrypted: String? = requestHeadersEncrypted
        protected set

    var updatedAt: Instant = createdAt
        protected set

    fun rename(name: String) {
        this.name = name
    }

    fun changeUrl(url: String) {
        this.url = url
    }

    fun changeHttpMethod(httpMethod: MonitorHttpMethod) {
        this.httpMethod = httpMethod
    }

    fun changeInterval(intervalMinutes: Int) {
        this.intervalMinutes = intervalMinutes
    }

    fun changeTimeout(timeoutSeconds: Int) {
        this.timeoutSeconds = timeoutSeconds
    }

    fun changeExpectedStatusCodes(expectedStatusCodes: String?) {
        this.expectedStatusCodes = expectedStatusCodes
    }

    fun changeRequestHeaders(requestHeadersEncrypted: String?) {
        this.requestHeadersEncrypted = requestHeadersEncrypted
    }

    fun touch(now: Instant) {
        this.updatedAt = now
    }
}
