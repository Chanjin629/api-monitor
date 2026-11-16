import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { MonitorForm } from '../features/monitors/MonitorForm'
import {
  createMonitor,
  emptyFormValues,
  fetchMonitor,
  formValuesFrom,
  monitorKeys,
  toFieldErrors,
  toPayload,
  updateMonitor,
  type MonitorFormValues,
} from '../features/monitors/monitorApi'

type MonitorFormPageProps = {
  mode: 'create' | 'edit'
}

/** S-04 모니터 등록, S-06 모니터 수정. 입력 항목이 같아 한 화면을 공유한다. */
export function MonitorFormPage({ mode }: MonitorFormPageProps) {
  const params = useParams()
  const id = Number(params.id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({})

  const existing = useQuery({
    queryKey: monitorKeys.detail(id),
    queryFn: () => fetchMonitor(id),
    enabled: mode === 'edit',
  })

  const save = useMutation({
    mutationFn: (values: MonitorFormValues) =>
      mode === 'create' ? createMonitor(toPayload(values)) : updateMonitor(id, toPayload(values)),
    onSuccess: (monitor) => {
      queryClient.setQueryData(monitorKeys.detail(monitor.id), monitor)
      queryClient.invalidateQueries({ queryKey: monitorKeys.all, exact: true })
      navigate(`/monitors/${monitor.id}`)
    },
    onError: (error) => setServerErrors(toFieldErrors(error)),
  })

  function handleSubmit(values: MonitorFormValues) {
    setServerErrors({})
    save.mutate(values)
  }

  const title = mode === 'create' ? '모니터 등록' : '모니터 수정'

  if (mode === 'edit' && existing.isPending) {
    return (
      <section>
        <h1 className="text-2xl font-bold">{title}</h1>
        <div className="mt-6 h-96 animate-pulse rounded-lg bg-slate-200" />
      </section>
    )
  }

  if (mode === 'edit' && existing.isError) {
    return (
      <section>
        <h1 className="text-2xl font-bold">{title}</h1>
        <p role="alert" className="mt-6 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {existing.error.message}
        </p>
      </section>
    )
  }

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold">{title}</h1>
      <MonitorForm
        // 수정할 모니터를 불러온 뒤 그 값으로 폼을 처음부터 만든다.
        key={existing.data?.id ?? 'new'}
        initialValues={existing.data ? formValuesFrom(existing.data) : emptyFormValues}
        savedHeaderNames={existing.data?.headers.map((header) => header.name)}
        serverErrors={serverErrors}
        submitting={save.isPending}
        submitLabel={mode === 'create' ? '등록' : '저장'}
        onSubmit={handleSubmit}
        onCancel={() => navigate(mode === 'create' ? '/dashboard' : `/monitors/${id}`)}
      />
    </section>
  )
}
