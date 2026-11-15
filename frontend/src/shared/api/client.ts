import { ApiError, NETWORK_ERROR, UNKNOWN_ERROR } from './ApiError'
import { tokenStore } from './tokenStore'
import type { ApiErrorBody, ApiSuccessBody } from './types'

const API_BASE = '/api/v1'
const REFRESH_PATH = '/auth/refresh'
/** 다른 탭이 토큰을 교체한 직후라면 쿠키가 바뀔 시간을 잠깐 준 뒤 다시 시도한다. */
const REFRESH_RETRY_DELAY_MS = 300

/** 재발급까지 실패해 다시 로그인해야 할 때 실행할 동작. main.tsx에서 랜딩 이동을 연결한다. */
let onSessionExpired: () => void = () => {}

export function setSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler
}

/**
 * API를 호출하고 성공 응답의 data를 돌려준다. 실패하면 ApiError를 던진다.
 * Access Token이 만료되면(AUTH_TOKEN_EXPIRED) 재발급한 뒤 같은 요청을 한 번 다시 보낸다.
 */
export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(path, init)

  if (response.status === 401 && path !== REFRESH_PATH) {
    const error = await toApiError(response)
    if (error.code === 'AUTH_TOKEN_EXPIRED') {
      if (await refreshAccessToken()) {
        return parse<T>(await send(path, init))
      }
      onSessionExpired()
    }
    throw error
  }

  return parse<T>(response)
}

/**
 * Refresh Token 쿠키로 Access Token을 다시 받는다. 성공하면 true.
 * 여러 요청이 동시에 만료되어도 재발급 요청은 하나만 보내도록 진행 중인 요청을 공유한다.
 */
let refreshing: Promise<boolean> | null = null

export function refreshAccessToken(): Promise<boolean> {
  refreshing ??= refreshWithRetry().finally(() => {
    refreshing = null
  })
  return refreshing
}

async function refreshWithRetry(): Promise<boolean> {
  if (await requestNewToken()) return true
  // 다른 탭이 같은 토큰으로 먼저 재발급했다면 서버는 이 요청을 거절하지만,
  // 브라우저 쿠키는 이미 새 토큰으로 바뀌어 있으므로 한 번 더 시도한다.
  await new Promise((resolve) => setTimeout(resolve, REFRESH_RETRY_DELAY_MS))
  return requestNewToken()
}

async function requestNewToken(): Promise<boolean> {
  try {
    const data = await parse<{ accessToken: string }>(await send(REFRESH_PATH, { method: 'POST' }))
    tokenStore.set(data.accessToken)
    return true
  } catch {
    tokenStore.clear()
    return false
  }
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = tokenStore.get()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  try {
    // Refresh Token 쿠키(/api/v1/auth 경로)가 함께 전송되도록 같은 출처의 쿠키를 포함한다.
    return await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.')
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw await toApiError(response)
  }
  const body = (await response.json()) as ApiSuccessBody<T>
  return body.data
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as ApiErrorBody
    const { code, message, details, traceId } = body.error
    return new ApiError(response.status, code, message, details, traceId)
  } catch {
    // 서버가 꺼져 있어 프록시가 HTML 오류를 돌려주는 경우 등
    return new ApiError(response.status, UNKNOWN_ERROR, '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  }
}
