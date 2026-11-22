/** 그래프에 그릴 점 하나. 값이 null이면 그 자리에서 선이 끊긴다. */
export type ChartPoint = {
  /** 밀리초 시각 */
  time: number
  average: number | null
  min: number | null
  max: number | null
}

export type StatsPointInput = {
  time: string
  averageResponseTimeMs: number | null
  minResponseTimeMs: number | null
  maxResponseTimeMs: number | null
}

/**
 * 서버가 준 점을 그래프용으로 바꾼다.
 * 서버는 검사가 없던 구간의 점을 보내지 않으므로, 점 사이가 한 구간보다 멀면 빈 점을 끼워 선을 끊는다.
 * 그러지 않으면 기록이 없던 시간을 가로질러 선이 이어져 정상이었던 것처럼 보인다.
 */
export function toChartData(points: StatsPointInput[], bucketMinutes: number): ChartPoint[] {
  const bucketMs = bucketMinutes * 60_000
  const sorted = points
    .map((point) => ({
      time: new Date(point.time).getTime(),
      average: point.averageResponseTimeMs,
      min: point.minResponseTimeMs,
      max: point.maxResponseTimeMs,
    }))
    .sort((a, b) => a.time - b.time)

  const result: ChartPoint[] = []
  sorted.forEach((point, index) => {
    const previous = sorted[index - 1]
    if (previous && point.time - previous.time > bucketMs) {
      result.push({ time: previous.time + bucketMs, average: null, min: null, max: null })
    }
    result.push(point)
  })
  return result
}
