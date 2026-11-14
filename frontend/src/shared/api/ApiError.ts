import type { ApiErrorDetail } from './types'

/** 서버가 없는 경우처럼 응답 본문으로 원인을 알 수 없을 때 쓰는 코드 */
export const NETWORK_ERROR = 'NETWORK_ERROR'
export const UNKNOWN_ERROR = 'UNKNOWN_ERROR'

/**
 * API 실패를 나타내는 에러. 화면은 message 문구가 아니라 code로 분기한다.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: ApiErrorDetail[]
  readonly traceId?: string

  constructor(status: number, code: string, message: string, details: ApiErrorDetail[] = [], traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
    this.traceId = traceId
  }
}
