import { describe, expect, it } from 'vitest'
import { formatDuration, formatOngoing, formatPercent, formatRelative } from './format'

const now = new Date('2026-11-22T12:00:00Z').getTime()
const ago = (seconds: number) => new Date(now - seconds * 1000).toISOString()

describe('formatRelative', () => {
  it('지난 시간을 가장 큰 단위로 보여 준다', () => {
    expect(formatRelative(ago(3), now)).toBe('방금 전')
    expect(formatRelative(ago(32), now)).toBe('32초 전')
    expect(formatRelative(ago(5 * 60 + 10), now)).toBe('5분 전')
    expect(formatRelative(ago(3 * 3600), now)).toBe('3시간 전')
    expect(formatRelative(ago(2 * 86400), now)).toBe('2일 전')
    expect(formatRelative(null, now)).toBe('-')
  })
})

describe('formatDuration', () => {
  it('분, 시간, 일 단위로 줄여 쓴다', () => {
    expect(formatDuration(30)).toBe('1분 미만')
    expect(formatDuration(20 * 60)).toBe('20분')
    expect(formatDuration(3600)).toBe('1시간')
    expect(formatDuration(65 * 60)).toBe('1시간 5분')
    expect(formatDuration(27 * 3600)).toBe('1일 3시간')
  })

  it('진행 중인 장애는 "째"를 붙인다', () => {
    expect(formatOngoing(ago(12 * 60), now)).toBe('12분째')
  })
})

describe('formatPercent', () => {
  it('소수 둘째 자리까지 보여 주고, 값이 없으면 대시다', () => {
    expect(formatPercent(99.857)).toBe('99.86%')
    expect(formatPercent(100)).toBe('100.00%')
    expect(formatPercent(null)).toBe('-')
  })
})
