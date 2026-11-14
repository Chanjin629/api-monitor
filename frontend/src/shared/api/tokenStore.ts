/**
 * Access Token 보관소. XSS 피해를 줄이려고 localStorage가 아닌 메모리에만 둔다 (결정 D1).
 * 새로고침하면 사라지며, 그때는 Refresh Token 쿠키로 다시 발급받는다.
 */
let accessToken: string | null = null

export const tokenStore = {
  get: () => accessToken,
  set: (token: string) => {
    accessToken = token
  },
  clear: () => {
    accessToken = null
  },
}
