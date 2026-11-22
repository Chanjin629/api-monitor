import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { toChartData, type StatsPointInput } from './chartData'

type ResponseTimeChartProps = {
  points: StatsPointInput[]
  bucketMinutes: number
}

/** 응답 시간 그래프. 검사가 없던 구간은 선을 끊어서 보여 준다. */
export function ResponseTimeChart({ points, bucketMinutes }: ResponseTimeChartProps) {
  const data = toChartData(points, bucketMinutes)
  // 24시간은 시:분, 7일·30일은 월/일로 축을 표시한다.
  const showDate = bucketMinutes >= 60

  if (data.length === 0) {
    return (
      <div className="flex h-60 items-center justify-center rounded-lg border border-dashed border-slate-300 text-sm text-slate-500">
        이 기간에 검사 기록이 없습니다
      </div>
    )
  }

  return (
    <div className="h-60" role="img" aria-label="응답 시간 그래프">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid stroke="#e2e8f0" vertical={false} />
          <XAxis
            dataKey="time"
            type="number"
            scale="time"
            domain={['dataMin', 'dataMax']}
            tickFormatter={(time: number) => formatAxis(time, showDate)}
            tick={{ fontSize: 12, fill: '#64748b' }}
            minTickGap={40}
          />
          <YAxis
            tick={{ fontSize: 12, fill: '#64748b' }}
            tickFormatter={(value: number) => `${value}ms`}
            width={64}
          />
          <Tooltip
            labelFormatter={(time) => new Date(Number(time)).toLocaleString()}
            formatter={(value, name) => [value == null ? '-' : `${value}ms`, name === 'average' ? '평균' : '최대']}
          />
          <Line
            type="monotone"
            dataKey="max"
            name="max"
            stroke="#cbd5e1"
            strokeWidth={1}
            dot={false}
            connectNulls={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="average"
            name="average"
            stroke="#0f172a"
            strokeWidth={2}
            dot={false}
            connectNulls={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

function formatAxis(time: number, showDate: boolean) {
  const date = new Date(time)
  const pad = (value: number) => String(value).padStart(2, '0')
  return showDate
    ? `${pad(date.getMonth() + 1)}/${pad(date.getDate())}`
    : `${pad(date.getHours())}:${pad(date.getMinutes())}`
}
