import { useParams } from 'react-router'
import { PagePlaceholder } from '../shared/ui/PagePlaceholder'

/** S-05 모니터 상세 */
export function MonitorDetailPage() {
  const { id } = useParams()

  return (
    <PagePlaceholder
      title={`모니터 #${id}`}
      phase="14단계"
      description="응답 시간 그래프, 장애 이력, 최근 검사 결과를 보여 줍니다."
    />
  )
}
