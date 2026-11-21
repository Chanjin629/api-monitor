import { apiRequest } from '../../shared/api/client'
import { ApiError } from '../../shared/api/ApiError'

/** docs/05-api-spec.md 7장 */
export type Channel = {
  id: number
  type: 'DISCORD'
  name: string
  /** 끝 4자리만 보인다. 예: ••••abcd */
  webhookUrlMasked: string
  enabled: boolean
  createdAt: string
}

export type ChannelPayload = {
  name?: string
  webhookUrl?: string
  enabled?: boolean
}

export const CHANNEL_LIMIT = 3

export const channelKeys = {
  all: ['notification-channels'] as const,
}

export function fetchChannels() {
  return apiRequest<{ items: Channel[] }>('/notification-channels')
}

export function createChannel(payload: ChannelPayload) {
  return apiRequest<Channel>('/notification-channels', {
    method: 'POST',
    body: JSON.stringify({ type: 'DISCORD', ...payload }),
  })
}

export function updateChannel(id: number, payload: ChannelPayload) {
  return apiRequest<Channel>(`/notification-channels/${id}`, { method: 'PATCH', body: JSON.stringify(payload) })
}

export function deleteChannel(id: number) {
  return apiRequest<null>(`/notification-channels/${id}`, { method: 'DELETE' })
}

export function sendTestMessage(id: number) {
  return apiRequest<{ delivered: boolean }>(`/notification-channels/${id}/test`, { method: 'POST' })
}

/** 서버와 같은 규칙(N-S6). 화면에서 먼저 확인하고, 최종 검증은 서버가 한다. */
const WEBHOOK_URL = /^https:\/\/discord\.com\/api\/webhooks\/\d{1,30}\/[A-Za-z0-9_-]{1,200}$/

export function isDiscordWebhookUrl(url: string): boolean {
  return WEBHOOK_URL.test(url.trim())
}

/** 서버 오류를 화면 문구로 바꾼다. (docs/06-screens.md S-08) */
export function channelErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  switch (error.code) {
    case 'WEBHOOK_URL_INVALID':
      return 'Discord Webhook 주소를 입력해 주세요'
    case 'CHANNEL_LIMIT_EXCEEDED':
      return `알림 채널은 ${CHANNEL_LIMIT}개까지 등록할 수 있습니다`
    case 'NOTIFICATION_SEND_FAILED':
      return '테스트 메시지를 보내지 못했습니다. 주소를 확인해 주세요.'
    case 'DEMO_READ_ONLY':
      return '데모에서는 알림 채널을 바꿀 수 없습니다.'
    default:
      return error.message
  }
}
