import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useMe } from '../features/auth/useMe'
import { ResponseTimeChart } from '../features/charts/ResponseTimeChart'
import { IncidentTable } from '../features/incidents/IncidentTable'
import { fetchIncidents, incidentKeys } from '../features/incidents/incidentApi'
import {
  STATS_RANGES,
  deleteMonitor,
  fetchChecks,
  fetchMonitor,
  fetchStats,
  monitorKeys,
  pauseMonitor,
  resultLabel,
  resumeMonitor,
  statusDisplay,
  type Monitor,
  type StatsRange,
} from '../features/monitors/monitorApi'
import { formatPercent, formatTime } from '../shared/lib/format'

/** S-05 모니터 상세: 설정과 현재 상태, 응답 시간 그래프, 장애 이력, 최근 검사 */
export function MonitorDetailPage() {
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: me } = useMe()
  const isDemo = me?.role === 'DEMO'
  const [actionError, setActionError] = useState<string | null>(null)

  const { data: monitor, isPending, isError, error } = useQuery({
    queryKey: monitorKeys.detail(id),
    queryFn: () => fetchMonitor(id),
    refetchInterval: 30_000,
  })

  const toggle = useMutation({
    mutationFn: (paused: boolean) => (paused ? resumeMonitor(id) : pauseMonitor(id)),
    onSuccess: (updated) => {
      setActionError(null)
      queryClient.setQueryData(monitorKeys.detail(id), updated)
      queryClient.invalidateQueries({ queryKey: monitorKeys.all, exact: true })
    },
    onError: (e) => setActionError(e.message),
  })

  const remove = useMutation({
    mutationFn: () => deleteMonitor(id),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: monitorKeys.detail(id) })
      queryClient.invalidateQueries({ queryKey: monitorKeys.all, exact: true })
      navigate('/dashboard', { replace: true })
    },
    onError: (e) => setActionError(e.message),
  })

  function handleDelete(target: Monitor) {
    if (window.confirm(`"${target.name}" 모니터를 삭제할까요?\n장애 이력도 함께 삭제됩니다.`)) {
      remove.mutate()
    }
  }

  if (isPending) return <div className="h-48 animate-pulse rounded-lg bg-slate-200" />

  if (isError) {
    return (
      <section>
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {error.message}
        </p>
        <Link to="/dashboard" className="mt-4 inline-block text-sm underline">
          대시보드로 돌아가기
        </Link>
      </section>
    )
  }

  const status = statusDisplay(monitor.state)
  const paused = monitor.state.status === 'PAUSED'
  const { state } = monitor

  return (
    <section>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-bold">{monitor.name}</h1>
        <span className={`rounded-full px-2.5 py-0.5 text-sm font-medium ${status.className}`}>{status.label}</span>
        {!isDemo && (
          <div className="ml-auto flex gap-2">
            <button
              type="button"
              disabled={toggle.isPending}
              onClick={() => toggle.mutate(paused)}
              className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-50"
            >
              {paused ? '재개' : '일시 정지'}
            </button>
            <Link
              to={`/monitors/${id}/edit`}
              className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50"
            >
              수정
            </Link>
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => handleDelete(monitor)}
              className="rounded-md border border-red-300 bg-white px-3 py-1.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
            >
              삭제
            </button>
          </div>
        )}
      </div>
      <p className="mt-2 break-all text-sm text-slate-600">
        {monitor.method} {monitor.url} · {monitor.intervalMinutes}분 주기
      </p>

      {actionError && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-800">
          {actionError}
        </p>
      )}
      {state.status === 'BLOCKED' && (
        <p className="mt-4 rounded-md bg-orange-50 px-3 py-2 text-sm text-orange-900">
          안전하지 않은 주소로 연결되어 검사를 멈췄습니다. URL을 확인해 주세요.
        </p>
      )}

      <dl className="mt-6 grid gap-4 rounded-lg border border-slate-200 bg-white p-6 text-sm sm:grid-cols-2">
        <Item label="Timeout">{monitor.timeoutSeconds}초</Item>
        <Item label="기대 상태 코드">
          {monitor.expectedStatusCodes.length > 0 ? monitor.expectedStatusCodes.join(', ') : '2xx'}
        </Item>
        <Item label="요청 헤더">
          {monitor.headers.length > 0 ? monitor.headers.map((header) => header.name).join(', ') : '없음'}
        </Item>
        <Item label="연속 실패">{state.consecutiveFailures}회</Item>
        <Item label="마지막 검사">
          {state.lastCheckedAt
            ? [
                new Date(state.lastCheckedAt).toLocaleString(),
                state.lastResult ? resultLabel(state.lastResult) : null,
                state.lastStatusCode,
                state.lastResponseTimeMs != null ? `${state.lastResponseTimeMs}ms` : null,
              ]
                .filter((part) => part != null)
                .join(' · ')
            : '첫 검사를 기다리는 중입니다'}
        </Item>
        <Item label="다음 검사">{state.nextCheckAt ? new Date(state.nextCheckAt).toLocaleString() : '-'}</Item>
      </dl>

      <StatsSection monitorId={id} />
      <IncidentSection monitorId={id} />
      <CheckSection monitorId={id} />
    </section>
  )
}

/** 가동률, 평균 응답 시간, 응답 시간 그래프. 기간 버튼으로 24시간·7일·30일을 바꾼다. */
function StatsSection({ monitorId }: { monitorId: number }) {
  const [range, setRange] = useState<StatsRange>('24h')
  const { data, isPending, isError, error } = useQuery({
    queryKey: monitorKeys.stats(monitorId, range),
    queryFn: () => fetchStats(monitorId, range),
    refetchInterval: 60_000,
  })

  return (
    <div className="mt-8">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
        <h2 className="text-lg font-semibold">응답 시간</h2>
        <span className="text-sm text-slate-600">
          가동률 <strong className="text-slate-900">{formatPercent(data?.uptimePercent)}</strong>
        </span>
        <span className="text-sm text-slate-600">
          평균 응답{' '}
          <strong className="text-slate-900">{data?.averageResponseTimeMs != null ? `${data.averageResponseTimeMs}ms` : '-'}</strong>
        </span>
        <div role="group" aria-label="기간" className="ml-auto flex gap-1">
          {STATS_RANGES.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={range === option.value}
              onClick={() => setRange(option.value)}
              className={`rounded-md px-3 py-1 text-sm ${
                range === option.value ? 'bg-slate-900 text-white' : 'border border-slate-300 bg-white hover:bg-slate-50'
              }`}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
      <div className="mt-3 rounded-lg border border-slate-200 bg-white p-4">
        {isPending && <div className="h-60 animate-pulse rounded bg-slate-100" />}
        {isError && <p className="text-sm text-red-700">{error.message}</p>}
        {data && <ResponseTimeChart points={data.points} bucketMinutes={data.bucketMinutes} />}
        {range !== '24h' && (
          <p className="mt-2 text-xs text-slate-500">7일·30일 그래프는 시간별 집계로 그려 최대 1시간 늦게 반영됩니다.</p>
        )}
      </div>
    </div>
  )
}

function IncidentSection({ monitorId }: { monitorId: number }) {
  const query = useInfiniteQuery({
    queryKey: incidentKeys.byMonitor(monitorId),
    queryFn: ({ pageParam }) => fetchIncidents({ monitorId, cursor: pageParam }),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    refetchInterval: 60_000,
  })

  return (
    <div className="mt-8">
      <h2 className="text-lg font-semibold">장애 이력</h2>
      <div className="mt-3">
        {query.isPending && <div className="h-20 animate-pulse rounded-lg bg-slate-200" />}
        {query.data && <IncidentTable incidents={query.data.pages.flatMap((page) => page.items)} />}
        <MoreButton visible={query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => query.fetchNextPage()} />
      </div>
    </div>
  )
}

function CheckSection({ monitorId }: { monitorId: number }) {
  const query = useInfiniteQuery({
    queryKey: monitorKeys.checks(monitorId),
    queryFn: ({ pageParam }) => fetchChecks(monitorId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    refetchInterval: 60_000,
  })
  const checks = query.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className="mt-8">
      <h2 className="text-lg font-semibold">최근 검사</h2>
      <div className="mt-3">
        {query.isPending && <div className="h-20 animate-pulse rounded-lg bg-slate-200" />}
        {query.data && checks.length === 0 && (
          <p className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-slate-600">
            첫 검사를 기다리는 중입니다
          </p>
        )}
        {checks.length > 0 && (
          <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50 text-slate-500">
                <tr>
                  <th className="px-4 py-2 font-medium">시각</th>
                  <th className="px-4 py-2 font-medium">결과</th>
                  <th className="px-4 py-2 font-medium">상태 코드</th>
                  <th className="px-4 py-2 font-medium">응답 시간</th>
                </tr>
              </thead>
              <tbody>
                {checks.map((check) => (
                  <tr key={check.checkedAt} className="border-b border-slate-100 last:border-0">
                    <td className="px-4 py-2 text-slate-600">{formatTime(check.checkedAt)}</td>
                    <td className={`px-4 py-2 ${check.result === 'SUCCESS' ? '' : 'text-red-700'}`}>{resultLabel(check.result)}</td>
                    <td className="px-4 py-2">{check.statusCode ?? '-'}</td>
                    <td className="px-4 py-2">{check.responseTimeMs != null ? `${check.responseTimeMs}ms` : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <MoreButton visible={query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => query.fetchNextPage()} />
      </div>
    </div>
  )
}

function MoreButton({ visible, loading, onClick }: { visible: boolean; loading: boolean; onClick: () => void }) {
  if (!visible) return null
  return (
    <div className="mt-3 text-center">
      <button
        type="button"
        disabled={loading}
        onClick={onClick}
        className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
      >
        {loading ? '불러오는 중...' : '더 보기'}
      </button>
    </div>
  )
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-slate-500">{label}</dt>
      <dd className="mt-0.5 font-medium">{children}</dd>
    </div>
  )
}
