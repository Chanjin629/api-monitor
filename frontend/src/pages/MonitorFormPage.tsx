import { useParams } from 'react-router'
import { PagePlaceholder } from '../shared/ui/PagePlaceholder'

type MonitorFormPageProps = {
  mode: 'create' | 'edit'
}

/** S-04 모니터 등록, S-06 모니터 수정. 입력 항목이 같아 한 화면을 공유한다. */
export function MonitorFormPage({ mode }: MonitorFormPageProps) {
  const { id } = useParams()
  const title = mode === 'create' ? '모니터 등록' : `모니터 수정 #${id}`

  return <PagePlaceholder title={title} phase="10단계" />
}
