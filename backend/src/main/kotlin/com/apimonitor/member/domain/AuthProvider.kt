package com.apimonitor.member.domain

/** 회원을 확인해 준 로그인 제공자 */
enum class AuthProvider {
    GITHUB,

    /** 데모 계정. 외부 로그인 없이 Flyway 시드로 만든다. */
    DEMO,
}
