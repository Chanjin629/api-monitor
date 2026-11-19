import { apiRequest } from '../../shared/api/client'
import { ApiError } from '../../shared/api/ApiError'

/** docs/05-api-spec.md 4장 */
export type MonitorStatus = 'UNKNOWN' | 'UP' | 'DOWN' | 'PAUSED' | 'BLOCKED'
export type HttpMethod = 'GET' | 'HEAD'

export type MonitorHeader = { name: string; value: string }

export type Monitor = {
  id: number
  name: string
  url: string
  method: HttpMethod
  intervalMinutes: number
  timeoutSeconds: number
  /** 비어 있으면 2xx 전체 */
  expectedStatusCodes: number[]
  /** 값은 항상 ******** 로 가려져 있다 */
  headers: MonitorHeader[]
  state: {
    status: MonitorStatus
    consecutiveFailures: number
    lastCheckedAt: string | null
    lastResult: string | null
    lastStatusCode: number | null
    lastResponseTimeMs: number | null
    nextCheckAt: string | null
  }
  uptime24h: number | null
  createdAt: string
  updatedAt: string
}

export type MonitorPayload = {
  name?: string
  url?: string
  method?: HttpMethod
  intervalMinutes?: number
  timeoutSeconds?: number
  expectedStatusCodes?: number[]
  headers?: MonitorHeader[]
}

export const INTERVAL_OPTIONS = [1, 5, 10, 30, 60] as const

export const monitorKeys = {
  all: ['monitors'] as const,
  detail: (id: number) => ['monitors', id] as const,
}

export function fetchMonitors() {
  return apiRequest<{ items: Monitor[]; nextCursor: string | null }>('/monitors')
}

export function fetchMonitor(id: number) {
  return apiRequest<Monitor>(`/monitors/${id}`)
}

export function createMonitor(payload: MonitorPayload) {
  return apiRequest<Monitor>('/monitors', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateMonitor(id: number, payload: MonitorPayload) {
  return apiRequest<Monitor>(`/monitors/${id}`, { method: 'PATCH', body: JSON.stringify(payload) })
}

export function deleteMonitor(id: number) {
  return apiRequest<null>(`/monitors/${id}`, { method: 'DELETE' })
}

export function pauseMonitor(id: number) {
  return apiRequest<Monitor>(`/monitors/${id}/pause`, { method: 'POST' })
}

export function resumeMonitor(id: number) {
  return apiRequest<Monitor>(`/monitors/${id}/resume`, { method: 'POST' })
}

// ---------- 입력 폼 값 변환 ----------

/** 입력 폼의 값. 숫자도 입력칸 그대로 문자열로 둔다. */
export type MonitorFormValues = {
  name: string
  url: string
  method: HttpMethod
  intervalMinutes: number
  timeoutSeconds: string
  /** 쉼표로 구분한 상태 코드. 비우면 2xx */
  expectedStatusCodes: string
  headers: MonitorHeader[]
  /**
   * 수정 화면에서 헤더를 새로 입력했는지.
   * 서버는 헤더 값을 가려서 주므로, 다시 입력하지 않았다면 headers를 보내지 않아 기존 값을 유지한다.
   */
  headersEdited: boolean
}

export const emptyFormValues: MonitorFormValues = {
  name: '',
  url: '',
  method: 'GET',
  intervalMinutes: 5,
  timeoutSeconds: '10',
  expectedStatusCodes: '',
  headers: [],
  headersEdited: true,
}

/** 수정 화면의 초깃값. 헤더 값은 가려져 있으므로 채우지 않는다. */
export function formValuesFrom(monitor: Monitor): MonitorFormValues {
  return {
    name: monitor.name,
    url: monitor.url,
    method: monitor.method,
    intervalMinutes: monitor.intervalMinutes,
    timeoutSeconds: String(monitor.timeoutSeconds),
    expectedStatusCodes: monitor.expectedStatusCodes.join(', '),
    headers: [],
    headersEdited: false,
  }
}

/** "200, 301" → [200, 301]. 숫자가 아닌 값이 있으면 null */
export function parseStatusCodes(text: string): number[] | null {
  const parts = text
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '')
  if (parts.some((part) => !/^\d+$/.test(part))) return null
  return parts.map(Number)
}

/** 화면에서 먼저 확인할 수 있는 입력 오류. 최종 검증은 서버가 한다. */
export function validateForm(values: MonitorFormValues): Record<string, string> {
  const errors: Record<string, string> = {}
  if (values.name.trim() === '') errors.name = '이름을 입력해 주세요.'
  if (values.url.trim() === '') errors.url = 'URL을 입력해 주세요.'
  else if (!/^https?:\/\//i.test(values.url.trim())) errors.url = 'http:// 또는 https://로 시작해야 합니다.'

  const timeout = Number(values.timeoutSeconds)
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 30) errors.timeoutSeconds = '1~30초 사이로 입력해 주세요.'

  const codes = parseStatusCodes(values.expectedStatusCodes)
  if (codes === null || codes.some((code) => code < 100 || code > 599)) {
    errors.expectedStatusCodes = '100~599 사이의 숫자를 쉼표로 구분해 입력해 주세요.'
  } else if (codes.length > 5) {
    errors.expectedStatusCodes = '최대 5개까지 지정할 수 있습니다.'
  }

  values.headers.forEach((header, index) => {
    if (header.name.trim() === '') errors[`headers[${index}].name`] = '헤더 이름을 입력해 주세요.'
  })
  return errors
}

/** 폼 값을 API 요청 본문으로 바꾼다. 검증을 통과한 값이라고 가정한다. */
export function toPayload(values: MonitorFormValues): MonitorPayload {
  const payload: MonitorPayload = {
    name: values.name.trim(),
    url: values.url.trim(),
    method: values.method,
    intervalMinutes: values.intervalMinutes,
    timeoutSeconds: Number(values.timeoutSeconds),
    expectedStatusCodes: parseStatusCodes(values.expectedStatusCodes) ?? [],
  }
  if (values.headersEdited) {
    payload.headers = values.headers.map((header) => ({ name: header.name.trim(), value: header.value }))
  }
  return payload
}

/** 서버 오류를 입력칸별 메시지로 바꾼다. 입력칸에 붙일 수 없는 오류는 form 키에 담는다. */
export function toFieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return { form: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.' }
  switch (error.code) {
    case 'URL_BLOCKED':
      return { url: '검사할 수 없는 URL입니다. 공개된 주소인지 확인해 주세요.' }
    case 'HEADER_NOT_ALLOWED':
      return Object.fromEntries(error.details.map((detail) => [detail.field, '지정할 수 없는 헤더입니다.']))
    case 'VALIDATION_FAILED':
      if (error.details.length > 0) {
        return Object.fromEntries(error.details.map((detail) => [detail.field, detail.reason]))
      }
      return { form: error.message }
    case 'MONITOR_LIMIT_EXCEEDED':
      return { form: '모니터는 20개까지 등록할 수 있습니다.' }
    case 'DEMO_READ_ONLY':
      return { form: '데모에서는 모니터를 등록하거나 바꿀 수 없습니다.' }
    default:
      return { form: error.message }
  }
}

// ---------- 상태 표시 ----------

/** docs/06-screens.md 3.2. 색만으로 구분하지 않도록 항상 글자와 함께 쓴다. */
export function statusDisplay(state: Monitor['state']): { label: string; className: string } {
  switch (state.status) {
    case 'DOWN':
      return { label: '장애', className: 'bg-red-100 text-red-800' }
    case 'UP':
      return state.consecutiveFailures > 0
        ? { label: '확인 중', className: 'bg-yellow-100 text-yellow-800' }
        : { label: '정상', className: 'bg-green-100 text-green-800' }
    case 'BLOCKED':
      return { label: '검사 차단', className: 'bg-orange-100 text-orange-800' }
    case 'PAUSED':
      return { label: '일시 정지', className: 'bg-slate-100 text-slate-600' }
    default:
      return { label: '확인 전', className: 'bg-slate-100 text-slate-600' }
  }
}

const RESULT_LABELS: Record<string, string> = {
  SUCCESS: '성공',
  TIMEOUT: '시간 초과',
  CONNECTION_ERROR: '연결 실패',
  DNS_ERROR: '도메인 조회 실패',
  TLS_ERROR: '인증서 오류',
  UNEXPECTED_STATUS: '예상과 다른 상태 코드',
  BLOCKED: '검사 차단',
}

/** 검사 결과 코드(docs/01-requirements.md 2.4)를 화면 문구로 바꾼다. 모르는 코드는 그대로 보여 준다. */
export function resultLabel(result: string): string {
  return RESULT_LABELS[result] ?? result
}

/** 대시보드 정렬 순서: 장애, 확인 중, 나머지 */
export function statusPriority(state: Monitor['state']): number {
  if (state.status === 'DOWN') return 0
  if (state.status === 'UP' && state.consecutiveFailures > 0) return 1
  return 2
}
