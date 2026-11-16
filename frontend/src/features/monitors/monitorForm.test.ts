import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/ApiError'
import {
  emptyFormValues,
  formValuesFrom,
  parseStatusCodes,
  statusDisplay,
  toFieldErrors,
  toPayload,
  validateForm,
  type Monitor,
} from './monitorApi'

const monitor: Monitor = {
  id: 1,
  name: '결제 API',
  url: 'https://api.example.com/health',
  method: 'GET',
  intervalMinutes: 1,
  timeoutSeconds: 10,
  expectedStatusCodes: [200, 301],
  headers: [{ name: 'Authorization', value: '********' }],
  state: {
    status: 'UP',
    consecutiveFailures: 0,
    lastCheckedAt: null,
    lastResult: null,
    lastStatusCode: null,
    lastResponseTimeMs: null,
    nextCheckAt: null,
  },
  uptime24h: null,
  createdAt: '2026-11-16T00:00:00Z',
  updatedAt: '2026-11-16T00:00:00Z',
}

describe('parseStatusCodes', () => {
  it('쉼표로 구분한 숫자를 배열로 바꾸고, 비어 있으면 빈 배열이다', () => {
    expect(parseStatusCodes('200, 301 ,')).toEqual([200, 301])
    expect(parseStatusCodes('')).toEqual([])
  })

  it('숫자가 아닌 값이 있으면 null이다', () => {
    expect(parseStatusCodes('200, abc')).toBeNull()
    expect(parseStatusCodes('2xx')).toBeNull()
  })
})

describe('validateForm', () => {
  it('필수 값, URL 스킴, Timeout 범위, 상태 코드를 확인한다', () => {
    const errors = validateForm({
      ...emptyFormValues,
      url: 'ftp://example.com',
      timeoutSeconds: '31',
      expectedStatusCodes: '200, 700',
      headers: [{ name: ' ', value: 'v' }],
    })

    expect(Object.keys(errors).sort()).toEqual(
      ['expectedStatusCodes', 'headers[0].name', 'name', 'timeoutSeconds', 'url'].sort(),
    )
  })

  it('올바른 값이면 오류가 없다', () => {
    expect(validateForm({ ...emptyFormValues, name: 'API', url: 'https://api.example.com' })).toEqual({})
  })
})

describe('toPayload', () => {
  it('등록할 때는 헤더를 포함하고 숫자 값을 숫자로 보낸다', () => {
    const payload = toPayload({
      ...emptyFormValues,
      name: ' API ',
      url: ' https://api.example.com ',
      timeoutSeconds: '15',
      expectedStatusCodes: '200',
      headers: [{ name: 'X-Key', value: 'secret' }],
    })

    expect(payload).toEqual({
      name: 'API',
      url: 'https://api.example.com',
      method: 'GET',
      intervalMinutes: 5,
      timeoutSeconds: 15,
      expectedStatusCodes: [200],
      headers: [{ name: 'X-Key', value: 'secret' }],
    })
  })

  it('수정 화면에서 헤더를 다시 입력하지 않으면 headers를 보내지 않아 기존 값을 유지한다', () => {
    const values = formValuesFrom(monitor)

    expect(values.headers).toEqual([])
    expect(values.expectedStatusCodes).toBe('200, 301')
    expect(toPayload(values)).not.toHaveProperty('headers')
    expect(toPayload({ ...values, headersEdited: true })).toHaveProperty('headers', [])
  })
})

describe('toFieldErrors', () => {
  it('서버 오류 코드를 입력칸별 메시지로 바꾼다', () => {
    expect(toFieldErrors(new ApiError(422, 'URL_BLOCKED', 'blocked'))).toHaveProperty('url')
    expect(
      toFieldErrors(new ApiError(422, 'HEADER_NOT_ALLOWED', 'x', [{ field: 'headers[1].name', reason: 'r' }])),
    ).toEqual({ 'headers[1].name': '지정할 수 없는 헤더입니다.' })
    expect(
      toFieldErrors(new ApiError(400, 'VALIDATION_FAILED', 'x', [{ field: 'intervalMinutes', reason: '주기 오류' }])),
    ).toEqual({ intervalMinutes: '주기 오류' })
    expect(toFieldErrors(new ApiError(409, 'MONITOR_LIMIT_EXCEEDED', 'x'))).toHaveProperty('form')
  })
})

describe('statusDisplay', () => {
  it('상태를 글자로 표시하고, 연속 실패가 있는 UP은 확인 중으로 표시한다', () => {
    expect(statusDisplay(monitor.state).label).toBe('정상')
    expect(statusDisplay({ ...monitor.state, consecutiveFailures: 1 }).label).toBe('확인 중')
    expect(statusDisplay({ ...monitor.state, status: 'DOWN' }).label).toBe('장애')
    expect(statusDisplay({ ...monitor.state, status: 'BLOCKED' }).label).toBe('검사 차단')
    expect(statusDisplay({ ...monitor.state, status: 'PAUSED' }).label).toBe('일시 정지')
    expect(statusDisplay({ ...monitor.state, status: 'UNKNOWN' }).label).toBe('확인 전')
  })
})
