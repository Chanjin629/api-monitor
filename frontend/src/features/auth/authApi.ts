import { apiRequest } from '../../shared/api/client'
import { tokenStore } from '../../shared/api/tokenStore'

export type Member = {
  id: number
  username: string
  displayName: string | null
  avatarUrl: string | null
  role: 'USER' | 'DEMO'
  monitorLimit: number
}

type AccessToken = {
  accessToken: string
  expiresIn: number
}

/** GitHub 로그인은 Backend(Spring Security)가 시작하므로 페이지를 이동한다. */
export function startGithubLogin() {
  window.location.assign('/oauth2/authorization/github')
}

/** 읽기 전용 데모 로그인. Refresh Token이 없어 새로고침하면 다시 시작해야 한다. */
export async function demoLogin() {
  const { accessToken } = await apiRequest<AccessToken>('/auth/demo', { method: 'POST' })
  tokenStore.set(accessToken)
}

/** 로그아웃. 서버 요청이 실패해도 이 기기의 토큰은 지운다. */
export async function logout({ allDevices = false } = {}) {
  try {
    await apiRequest<null>(allDevices ? '/auth/logout-all' : '/auth/logout', { method: 'POST' })
  } finally {
    tokenStore.clear()
  }
}

export function fetchMe() {
  return apiRequest<Member>('/members/me')
}

/** 회원 탈퇴. 모니터, 장애 이력, 알림 채널이 모두 삭제되어 되돌릴 수 없다. */
export async function withdraw() {
  try {
    await apiRequest<null>('/members/me', { method: 'DELETE' })
  } finally {
    tokenStore.clear()
  }
}
