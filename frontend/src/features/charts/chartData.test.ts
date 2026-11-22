import { describe, expect, it } from 'vitest'
import { toChartData } from './chartData'

const point = (time: string, average: number | null) => ({
  time,
  averageResponseTimeMs: average,
  minResponseTimeMs: average,
  maxResponseTimeMs: average,
})

describe('toChartData', () => {
  it('이어진 구간은 그대로 두고 시간순으로 정렬한다', () => {
    const data = toChartData([point('2026-11-22T00:05:00Z', 200), point('2026-11-22T00:00:00Z', 100)], 5)

    expect(data.map((p) => p.average)).toEqual([100, 200])
  })

  it('검사가 없던 구간에는 빈 점을 끼워 선을 끊는다', () => {
    const data = toChartData([point('2026-11-22T00:00:00Z', 100), point('2026-11-22T00:30:00Z', 150)], 5)

    expect(data).toHaveLength(3)
    expect(data[1]).toEqual({
      time: new Date('2026-11-22T00:05:00Z').getTime(),
      average: null,
      min: null,
      max: null,
    })
  })

  it('점이 없으면 빈 배열이다', () => {
    expect(toChartData([], 60)).toEqual([])
  })
})
