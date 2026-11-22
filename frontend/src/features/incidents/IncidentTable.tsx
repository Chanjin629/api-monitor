import { useNavigate } from 'react-router'
import { formatDateTime } from '../../shared/lib/format'
import { causeLabel, incidentDuration, type Incident } from './incidentApi'

type IncidentTableProps = {
  incidents: Incident[]
  /** 전체 장애 이력 화면처럼 여러 모니터를 보여 줄 때 모니터 열을 표시한다 */
  showMonitor?: boolean
}

/** 장애 이력 표. 행을 누르면 그 모니터의 상세 화면으로 이동한다. */
export function IncidentTable({ incidents, showMonitor = false }: IncidentTableProps) {
  const navigate = useNavigate()

  if (incidents.length === 0) {
    return (
      <p className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-slate-600">
        기록된 장애가 없습니다
      </p>
    )
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-slate-500">
          <tr>
            {showMonitor && <th className="px-4 py-2 font-medium">모니터</th>}
            <th className="px-4 py-2 font-medium">상태</th>
            <th className="px-4 py-2 font-medium">시작</th>
            <th className="px-4 py-2 font-medium">지속 시간</th>
            <th className="px-4 py-2 font-medium">원인</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((incident) => (
            <tr
              key={incident.id}
              onClick={showMonitor ? () => navigate(`/monitors/${incident.monitor.id}`) : undefined}
              className={`border-b border-slate-100 last:border-0 ${showMonitor ? 'cursor-pointer hover:bg-slate-50' : ''}`}
            >
              {showMonitor && <td className="px-4 py-3 font-medium">{incident.monitor.name}</td>}
              <td className="px-4 py-3">
                <span
                  className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                    incident.status === 'OPEN' ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-600'
                  }`}
                >
                  {incident.status === 'OPEN' ? '진행 중' : '복구'}
                </span>
              </td>
              <td className="px-4 py-3 text-slate-600">{formatDateTime(incident.startedAt)}</td>
              <td className="px-4 py-3">{incidentDuration(incident)}</td>
              <td className="px-4 py-3">{causeLabel(incident.cause, incident.lastStatusCode)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
