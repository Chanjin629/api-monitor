import { useInfiniteQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { IncidentTable } from '../features/incidents/IncidentTable'
import { fetchIncidents, incidentKeys, type IncidentStatus } from '../features/incidents/incidentApi'

const FILTERS: { value: IncidentStatus | null; label: string }[] = [
  { value: null, label: '전체' },
  { value: 'OPEN', label: '진행 중' },
  { value: 'RESOLVED', label: '복구' },
]

/** S-07 전체 장애 이력 */
export function IncidentsPage() {
  const [status, setStatus] = useState<IncidentStatus | null>(null)
  const query = useInfiniteQuery({
    queryKey: incidentKeys.list(status),
    queryFn: ({ pageParam }) => fetchIncidents({ status, cursor: pageParam }),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
  })
  const incidents = query.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-bold">장애 이력</h1>
        <div role="group" aria-label="상태로 거르기" className="ml-auto flex gap-1">
          {FILTERS.map((filter) => (
            <button
              key={filter.label}
              type="button"
              aria-pressed={status === filter.value}
              onClick={() => setStatus(filter.value)}
              className={`rounded-md px-3 py-1.5 text-sm ${
                status === filter.value ? 'bg-slate-900 text-white' : 'border border-slate-300 bg-white hover:bg-slate-50'
              }`}
            >
              {filter.label}
            </button>
          ))}
        </div>
      </div>

      {query.isPending && <div className="h-32 animate-pulse rounded-lg bg-slate-200" />}
      {query.isError && (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {query.error.message}
        </p>
      )}
      {query.data && <IncidentTable incidents={incidents} showMonitor />}

      {query.hasNextPage && (
        <div className="text-center">
          <button
            type="button"
            disabled={query.isFetchingNextPage}
            onClick={() => query.fetchNextPage()}
            className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
          >
            {query.isFetchingNextPage ? '불러오는 중...' : '더 보기'}
          </button>
        </div>
      )}
    </section>
  )
}
