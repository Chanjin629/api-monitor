package com.apimonitor.global.response

import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import java.time.Instant
import java.util.Base64

/**
 * 커서 방식 페이지의 커서 값. "이 항목 다음부터"를 나타내는 마지막 항목의 시각이나 ID를 Base64로 감싼다.
 * 클라이언트는 값을 해석하지 않고 그대로 돌려보내면 된다. 형식이 잘못되면 400 VALIDATION_FAILED다.
 */
object Cursor {

    fun ofInstant(value: Instant): String = encode("t:$value")

    fun ofId(value: Long): String = encode("id:$value")

    fun toInstant(cursor: String): Instant = parse(cursor, "t:") { Instant.parse(it) }

    fun toId(cursor: String): Long = parse(cursor, "id:") { it.toLong() }

    private fun encode(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

    private fun <T> parse(cursor: String, prefix: String, convert: (String) -> T): T =
        try {
            val raw = String(Base64.getUrlDecoder().decode(cursor))
            require(raw.startsWith(prefix))
            convert(raw.removePrefix(prefix))
        } catch (e: Exception) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("cursor", "올바르지 않은 커서입니다.")),
            )
        }
}
