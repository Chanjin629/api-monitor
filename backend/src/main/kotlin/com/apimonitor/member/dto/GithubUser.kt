package com.apimonitor.member.dto

/**
 * GitHub 사용자 정보 중 서비스에서 쓰는 값만 담는다.
 * 나머지 코드는 GitHub 응답 형식을 몰라도 된다.
 */
data class GithubUser(
    /** 바뀌지 않는 숫자 ID. 회원 식별 기준이다. */
    val id: String,
    /** 사용자가 바꿀 수 있는 로그인 이름 */
    val login: String,
    val name: String?,
    val avatarUrl: String?,
) {
    companion object {
        /** GitHub의 /user 응답(attributes)에서 필요한 값을 꺼낸다. */
        fun from(attributes: Map<String, Any?>): GithubUser = GithubUser(
            id = requireNotNull(attributes["id"]) { "GitHub 응답에 id가 없습니다." }.toString(),
            login = requireNotNull(attributes["login"]) { "GitHub 응답에 login이 없습니다." }.toString(),
            name = attributes["name"]?.toString()?.takeIf { it.isNotBlank() },
            avatarUrl = attributes["avatar_url"]?.toString(),
        )
    }
}
