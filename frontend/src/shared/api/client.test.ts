import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NETWORK_ERROR } from './ApiError'
import { apiRequest, refreshAccessToken, setSessionExpiredHandler } from './client'
import { tokenStore } from './tokenStore'

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ok = (data: unknown) => json(200, { success: true, data })
const fail = (status: number, code: string) =>
  json(status, { success: false, error: { code, message: code, details: [], traceId: 'trace-1' } })

const fetchMock = vi.fn<typeof fetch>()

function authHeaderOf(call: Parameters<typeof fetch>): string | null {
  return new Headers(call[1]?.headers).get('Authorization')
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  tokenStore.clear()
  setSessionExpiredHandler(() => {})
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('apiRequest', () => {
  it('성공 응답의 data를 돌려준다', async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: 1 }))

    await expect(apiRequest('/members/me')).resolves.toEqual({ id: 1 })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/members/me')
  })

  it('실패 응답은 코드와 traceId를 담은 ApiError가 된다', async () => {
    fetchMock.mockResolvedValueOnce(fail(404, 'MONITOR_NOT_FOUND'))

    const error = await apiRequest('/monitors/1').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 404, code: 'MONITOR_NOT_FOUND', traceId: 'trace-1' })
  })

  it('서버에 연결하지 못하면 NETWORK_ERROR가 된다', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(apiRequest('/members/me')).rejects.toMatchObject({ code: NETWORK_ERROR })
  })

  it('토큰이 만료되면 재발급한 뒤 새 토큰으로 같은 요청을 다시 보낸다', async () => {
    tokenStore.set('old-token')
    fetchMock
      .mockResolvedValueOnce(fail(401, 'AUTH_TOKEN_EXPIRED'))
      .mockResolvedValueOnce(ok({ accessToken: 'new-token', expiresIn: 900 }))
      .mockResolvedValueOnce(ok({ id: 1 }))

    await expect(apiRequest('/members/me')).resolves.toEqual({ id: 1 })

    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/refresh')
    expect(authHeaderOf(fetchMock.mock.calls[2])).toBe('Bearer new-token')
    expect(tokenStore.get()).toBe('new-token')
  })

  it('여러 요청이 동시에 만료되어도 재발급은 한 번만 보낸다', async () => {
    tokenStore.set('old-token')
    fetchMock.mockImplementation(async (input, init) => {
      if (input === '/api/v1/auth/refresh') return ok({ accessToken: 'new-token', expiresIn: 900 })
      const token = new Headers(init?.headers).get('Authorization')
      return token === 'Bearer new-token' ? ok('done') : fail(401, 'AUTH_TOKEN_EXPIRED')
    })

    const results = await Promise.all([apiRequest('/a'), apiRequest('/b'), apiRequest('/c')])

    expect(results).toEqual(['done', 'done', 'done'])
    const refreshCalls = fetchMock.mock.calls.filter(([input]) => input === '/api/v1/auth/refresh')
    expect(refreshCalls).toHaveLength(1)
  })

  it('재발급까지 실패하면 토큰을 지우고 세션 만료 처리를 실행한다', async () => {
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    tokenStore.set('old-token')
    fetchMock.mockImplementation(async (input) =>
      input === '/api/v1/auth/refresh' ? fail(401, 'AUTH_REFRESH_INVALID') : fail(401, 'AUTH_TOKEN_EXPIRED'),
    )

    await expect(apiRequest('/members/me')).rejects.toMatchObject({ code: 'AUTH_TOKEN_EXPIRED' })

    expect(onExpired).toHaveBeenCalledTimes(1)
    expect(tokenStore.get()).toBeNull()
  })

  it('만료가 아닌 401은 재발급하지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(fail(401, 'AUTH_REQUIRED'))

    await expect(apiRequest('/members/me')).rejects.toMatchObject({ code: 'AUTH_REQUIRED' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('refreshAccessToken', () => {
  it('첫 재발급이 거절되면 다른 탭이 바꾼 쿠키로 한 번 더 시도한다', async () => {
    fetchMock
      .mockResolvedValueOnce(fail(401, 'AUTH_REFRESH_INVALID'))
      .mockResolvedValueOnce(ok({ accessToken: 'from-retry', expiresIn: 900 }))

    await expect(refreshAccessToken()).resolves.toBe(true)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(tokenStore.get()).toBe('from-retry')
  })

  it('두 번 모두 거절되면 false를 돌려준다', async () => {
    fetchMock.mockResolvedValue(fail(401, 'AUTH_REFRESH_INVALID'))

    await expect(refreshAccessToken()).resolves.toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
