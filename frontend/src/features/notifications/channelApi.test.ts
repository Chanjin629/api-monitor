import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/ApiError'
import { channelErrorMessage, isDiscordWebhookUrl } from './channelApi'

describe('isDiscordWebhookUrl', () => {
  it('Discord Webhook 주소만 받는다', () => {
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/123456/abc_DEF-123')).toBe(true)
    expect(isDiscordWebhookUrl('  https://discord.com/api/webhooks/1/token  ')).toBe(true)
  })

  it('다른 도메인, http, 쿼리 문자열이 붙은 주소는 거부한다', () => {
    expect(isDiscordWebhookUrl('http://discord.com/api/webhooks/1/token')).toBe(false)
    expect(isDiscordWebhookUrl('https://discord.com.evil.example/api/webhooks/1/token')).toBe(false)
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/1/token?wait=true')).toBe(false)
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/abc/token')).toBe(false)
    expect(isDiscordWebhookUrl('')).toBe(false)
  })
})

describe('channelErrorMessage', () => {
  it('오류 코드별로 화면 문구를 돌려준다', () => {
    expect(channelErrorMessage(new ApiError(422, 'WEBHOOK_URL_INVALID', 'x'))).toBe('Discord Webhook 주소를 입력해 주세요')
    expect(channelErrorMessage(new ApiError(409, 'CHANNEL_LIMIT_EXCEEDED', 'x'))).toBe(
      '알림 채널은 3개까지 등록할 수 있습니다',
    )
    expect(channelErrorMessage(new ApiError(502, 'NOTIFICATION_SEND_FAILED', 'x'))).toContain('테스트 메시지를 보내지 못했습니다')
    expect(channelErrorMessage(new Error('boom'))).toContain('잠시 후 다시 시도')
  })
})
