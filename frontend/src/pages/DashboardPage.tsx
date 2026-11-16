import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { useMe } from '../features/auth/useMe'
import { fetchMonitors, monitorKeys, statusDisplay, statusPriority } from '../features/monitors/monitorApi'

/**
 * S-03 대시보드. 이번 단계는 모니터 목록만 보여 준다.
 * 요약 카드와 최근 장애는 집계 기능과 함께 14단계에서 추가한다.
 */
export function DashboardPage() {
  const { data: me } = useMe()
  const navigate = useNavigate()
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: monitorKeys.all,
    queryFn: fetchMonitors,
    refetchInterval: 30_000,
  })

  const monitors = [...(data?.items ?? [])].sort((a, b) => statusPriority(a.state) - statusPriority(b.state))
  const isDemo = me?.role === 'DEMO'
  const limit = me?.monitorLimit ?? 20
  const atLimit = monitors.length >= limit

  return (
    <section>
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

      <h2 className="mt-8 text-lg font-semibold">
        모니터 <span className="text-sm font-normal text-slate-500">{data ? `${monitors.length} / ${limit}` : ''}</span>
      </h2>

      {isPending && <div className="mt-3 h-32 animate-pulse rounded-lg bg-slate-200" />}

      {isError && (
        <div role="alert" className="mt-3 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {error.message}
          <button type="button" onClick={() => refetch()} className="ml-3 underline">
            다시 시도
          </button>
        </div>
      )}

      {data && monitors.length === 0 && (
        <div className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
          <p className="text-slate-600">첫 모니터를 등록해 보세요</p>
          {!isDemo && (
            <Link
              to="/monitors/new"
              className="mt-4 inline-block rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
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
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${status.className}`}>
                        {status.label}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {monitor.state.lastResponseTimeMs != null ? `${monitor.state.lastResponseTimeMs}ms` : '-'}
                    </td>
                    <td className="px-4 py-3">{monitor.uptime24h != null ? `${monitor.uptime24h.toFixed(2)}%` : '-'}</td>
                    <td className="px-4 py-3 text-slate-500">
                      {monitor.state.lastCheckedAt ? new Date(monitor.state.lastCheckedAt).toLocaleString() : '-'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
