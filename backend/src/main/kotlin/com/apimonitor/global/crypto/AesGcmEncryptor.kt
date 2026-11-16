package com.apimonitor.global.crypto

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** application.yml의 crypto 설정. secret은 Base64로 인코딩한 32바이트 키다. */
@ConfigurationProperties("crypto")
data class CryptoProperties(
    val secret: String,
)

/**
 * 요청 헤더 값처럼 다시 원문이 필요한 비밀값을 AES-GCM으로 암호화한다.
 * 같은 값도 매번 다른 IV(초기화 벡터)를 써서 암호문이 달라지고, GCM 인증 태그로 변조를 감지한다.
 */
@Component
class AesGcmEncryptor(properties: CryptoProperties) {

    private val key = SecretKeySpec(
        Base64.getDecoder().decode(properties.secret).also {
            require(it.size == KEY_BYTES) { "crypto.secret must be a Base64-encoded 32-byte key" }
        },
        "AES",
    )
    private val secureRandom = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return VERSION_PREFIX + Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(encoded: String): String {
        require(encoded.startsWith(VERSION_PREFIX)) { "Unknown encryption format" }
        val bytes = Base64.getDecoder().decode(encoded.removePrefix(VERSION_PREFIX))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        /** 나중에 키나 방식을 바꿀 때 구분하기 위한 표시 */
        private const val VERSION_PREFIX = "v1:"
    }
}
