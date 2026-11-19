import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useMe } from '../features/auth/useMe'
import {
  deleteMonitor,
  fetchMonitor,
  monitorKeys,
  pauseMonitor,
  resultLabel,
  resumeMonitor,
  statusDisplay,
  type Monitor,
} from '../features/monitors/monitorApi'

/**
 * S-05 모니터 상세. 이번 단계는 설정과 현재 상태, 정지·재개·수정·삭제만 다룬다.
 * 응답 시간 그래프, 장애 이력, 최근 검사 목록은 14단계에서 추가한다.
 */
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
    </section>
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
