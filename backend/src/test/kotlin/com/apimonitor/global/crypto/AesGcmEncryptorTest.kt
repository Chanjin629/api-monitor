package com.apimonitor.global.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AesGcmEncryptorTest {

    private val encryptor = AesGcmEncryptor(CryptoProperties("i+RSNH2n2xUbjYhYY7fX0gt90FUzjUInH7OvAXxIiyY="))

    @Test
    fun `암호화한 값을 복호화하면 원문이 나온다`() {
        val encrypted = encryptor.encrypt("Bearer secret-token 한글")

        assertThat(encrypted).doesNotContain("secret-token")
        assertThat(encryptor.decrypt(encrypted)).isEqualTo("Bearer secret-token 한글")
    }

    @Test
    fun `같은 값도 매번 다른 암호문이 된다`() {
        assertThat(encryptor.encrypt("same")).isNotEqualTo(encryptor.encrypt("same"))
    }

    @Test
    fun `암호문을 바꾸면 복호화에 실패한다`() {
        val encrypted = encryptor.encrypt("value")
        val tampered = encrypted.dropLast(2) + if (encrypted.endsWith("AA")) "BB" else "AA"

        assertThatThrownBy { encryptor.decrypt(tampered) }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `다른 키로는 복호화할 수 없다`() {
        val other = AesGcmEncryptor(CryptoProperties("JQzQP0ee2rTUBZShO+OpCE+lM8o7ybpk0BT3otKOkVY="))

        assertThatThrownBy { other.decrypt(encryptor.encrypt("value")) }.isInstanceOf(Exception::class.java)
    }
}
