import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { useMe } from '../features/auth/useMe'
import { causeLabel } from '../features/incidents/incidentApi'
import {
  fetchMonitors,
  fetchSummary,
  monitorKeys,
  resultLabel,
  statusDisplay,
  statusPriority,
} from '../features/monitors/monitorApi'
import { channelKeys, fetchChannels } from '../features/notifications/channelApi'
import { formatOngoing, formatPercent, formatRelative } from '../shared/lib/format'

const REFRESH_MS = 30_000

/** S-03 대시보드. 요약과 모니터 목록은 30초마다 갱신한다. */
export function DashboardPage() {
  const { data: me } = useMe()
  const navigate = useNavigate()
  const isDemo = me?.role === 'DEMO'
  const summary = useQuery({ queryKey: monitorKeys.summary, queryFn: fetchSummary, refetchInterval: REFRESH_MS })
  const list = useQuery({ queryKey: monitorKeys.all, queryFn: fetchMonitors, refetchInterval: REFRESH_MS })
  // 데모 계정은 채널을 등록할 수 없으므로 안내하지 않는다.
  const channels = useQuery({ queryKey: channelKeys.all, queryFn: fetchChannels, enabled: me != null && !isDemo })

  const monitors = [...(list.data?.items ?? [])].sort((a, b) => statusPriority(a.state) - statusPriority(b.state))
  const limit = me?.monitorLimit ?? 20
  const atLimit = monitors.length >= limit

  return (
    <section className="space-y-8">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-bold">대시보드</h1>
        {!isDemo && (
          <div className="ml-auto flex items-center gap-3">
            {atLimit && <span className="text-sm text-slate-500">최대 {limit}개까지 등록할 수 있습니다</span>}
            <button
              type="button"
              disabled={atLimit}
              onClick={() => navigate('/monitors/new')}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
            >
              + 모니터 추가
            </button>
          </div>
        )}
      </div>

      {channels.data && channels.data.items.length === 0 && (
        <p className="rounded-md bg-amber-50 px-4 py-3 text-sm text-amber-900">
          알림 채널을 등록해야 장애 알림을 받을 수 있습니다.{' '}
          <Link to="/settings" className="font-medium underline">
            설정에서 등록하기
          </Link>
        </p>
      )}

      {summary.isPending ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[0, 1, 2, 3].map((key) => (
            <div key={key} className="h-20 animate-pulse rounded-lg bg-slate-200" />
          ))}
        </div>
      ) : summary.data ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <SummaryCard label="전체" value={String(summary.data.totalMonitors)} />
          <SummaryCard label="정상" value={String(summary.data.upCount)} tone="text-green-700" />
          <SummaryCard label="장애" value={String(summary.data.downCount)} tone={summary.data.downCount > 0 ? 'text-red-700' : undefined} />
          <SummaryCard label="24시간 가동률" value={formatPercent(summary.data.uptime24h)} />
        </div>
      ) : null}

      {summary.data && summary.data.recentIncidents.length > 0 && (
        <div>
          <h2 className="text-lg font-semibold">진행 중이거나 최근의 장애</h2>
          <ul className="mt-3 divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white">
            {summary.data.recentIncidents.map((incident) => (
              <li key={incident.id} className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm">
                <span
                  aria-hidden
                  className={`h-2.5 w-2.5 rounded-full ${incident.status === 'OPEN' ? 'bg-red-500' : 'bg-slate-300'}`}
                />
                <span className="font-medium">{incident.monitor.name}</span>
                <span className={incident.status === 'OPEN' ? 'text-red-700' : 'text-slate-500'}>
                  {incident.status === 'OPEN' ? `장애 ${formatOngoing(incident.startedAt)}` : '복구됨'}
                </span>
                <span className="text-slate-500">{causeLabel(incident.cause, incident.lastStatusCode)}</span>
                <Link to={`/monitors/${incident.monitor.id}`} className="ml-auto text-slate-600 underline">
                  보기
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div>
        <h2 className="text-lg font-semibold">
          모니터 <span className="text-sm font-normal text-slate-500">{list.data ? `${monitors.length} / ${limit}` : ''}</span>
        </h2>

        {list.isPending && <div className="mt-3 h-32 animate-pulse rounded-lg bg-slate-200" />}

        {list.isError && (
          <div role="alert" className="mt-3 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {list.error.message}
            <button type="button" onClick={() => list.refetch()} className="ml-3 underline">
              다시 시도
            </button>
          </div>
        )}

        {list.data && monitors.length === 0 && (
          <div className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
            <p className="text-slate-600">첫 모니터를 등록해 보세요</p>
            {!isDemo && (
              <Link to="/monitors/new" className="mt-4 inline-block rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white">
                모니터 등록
              </Link>
            )}
          </div>
        )}

        {monitors.length > 0 && (
          <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50 text-slate-500">
                <tr>
                  <th className="px-4 py-2 font-medium">이름</th>
                  <th className="px-4 py-2 font-medium">상태</th>
                  <th className="px-4 py-2 font-medium">응답 시간</th>
                  <th className="px-4 py-2 font-medium">24시간 가동률</th>
                  <th className="px-4 py-2 font-medium">마지막 검사</th>
                </tr>
              </thead>
              <tbody>
                {monitors.map((monitor) => {
                  const status = statusDisplay(monitor.state)
                  return (
                    <tr key={monitor.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50">
                      <td className="px-4 py-3">
                        <Link to={`/monitors/${monitor.id}`} className="font-medium hover:underline">
                          {monitor.name}
                        </Link>
                        <div className="max-w-xs truncate text-xs text-slate-500">{monitor.url}</div>
                      </td>
                      <td className="px-4 py-3">
                        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${status.className}`}>{status.label}</span>
                      </td>
                      <td className="px-4 py-3">
                        {monitor.state.lastResponseTimeMs != null
                          ? `${monitor.state.lastResponseTimeMs}ms`
                          : monitor.state.lastResult
                            ? resultLabel(monitor.state.lastResult)
                            : '-'}
                      </td>
                      <td className="px-4 py-3">{formatPercent(monitor.uptime24h)}</td>
                      <td className="px-4 py-3 text-slate-500">{formatRelative(monitor.state.lastCheckedAt)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}

function SummaryCard({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-sm text-slate-500">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${tone ?? ''}`}>{value}</p>
    </div>
  )
}
