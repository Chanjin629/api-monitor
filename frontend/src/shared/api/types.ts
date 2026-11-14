/** 서버 공통 응답 형식. docs/05-api-spec.md 1.1절 */
export type ApiSuccessBody<T> = {
  success: true
  data: T
}

export type ApiErrorDetail = {
  field: string
  reason: string
}

export type ApiErrorBody = {
  success: false
  error: {
    code: string
    message: string
    details: ApiErrorDetail[]
    traceId: string
  }
}
