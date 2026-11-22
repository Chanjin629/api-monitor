import { apiRequest } from '../../shared/api/client'
import { formatDuration, formatOngoing } from '../../shared/lib/format'

/** docs/05-api-spec.md 5장 */
export type IncidentStatus = 'OPEN' | 'RESOLVED'

export type Incident = {
  id: number
  monitor: { id: number; name: string }
  status: IncidentStatus
  startedAt: string
  detectedAt: string
  resolvedAt: string | null
  durationSeconds: number | null
  cause: string
  lastStatusCode: number | null
}

export type IncidentPage = { items: Incident[]; nextCursor: string | null }

export const incidentKeys = {
  all: ['incidents'] as const,
  list: (status: IncidentStatus | null) => ['incidents', 'all', status] as const,
  byMonitor: (monitorId: number) => ['incidents', 'monitor', monitorId] as const,
}

export function fetchIncidents({
  monitorId,
  status,
  cursor,
}: {
  monitorId?: number
  status?: IncidentStatus | null
  cursor?: string | null
}) {
  const params = new URLSearchParams()
  if (status) params.set('status', status)
  if (cursor) params.set('cursor', cursor)
  const query = params.size > 0 ? `?${params}` : ''
  const path = monitorId == null ? '/incidents' : `/monitors/${monitorId}/incidents`
  return apiRequest<IncidentPage>(`${path}${query}`)
}

/** 원인 코드를 화면 문구로 바꾼다. 서버의 Discord 메시지와 같은 규칙이다. */
export function causeLabel(cause: string, statusCode: number | null): string {
  switch (cause) {
    case 'UNEXPECTED_STATUS':
      return statusCode != null ? `HTTP ${statusCode}` : '예상과 다른 상태 코드'
    case 'TIMEOUT':
      return '시간 초과'
    case 'CONNECTION_ERROR':
      return '연결 실패'
    case 'DNS_ERROR':
      return '도메인 조회 실패'
    case 'TLS_ERROR':
      return '인증서 오류'
    default:
      return cause
  }
}

/** 끝난 장애는 저장된 지속 시간, 진행 중인 장애는 지금까지의 시간("12분째") */
export function incidentDuration(incident: Incident, now: number = Date.now()): string {
  if (incident.durationSeconds != null) return formatDuration(incident.durationSeconds)
  return formatOngoing(incident.startedAt, now)
}
