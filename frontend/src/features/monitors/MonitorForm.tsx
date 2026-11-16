import { useState, type FormEvent, type ReactNode } from 'react'
import {
  INTERVAL_OPTIONS,
  validateForm,
  type HttpMethod,
  type MonitorFormValues,
  type MonitorHeader,
} from './monitorApi'

type MonitorFormProps = {
  initialValues: MonitorFormValues
  /** 수정 화면에서 이미 저장된 헤더 이름. 값은 서버가 가려서 주므로 이름만 보여 준다. */
  savedHeaderNames?: string[]
  /** 서버가 돌려준 입력칸별 오류 */
  serverErrors: Record<string, string>
  submitting: boolean
  submitLabel: string
  onSubmit: (values: MonitorFormValues) => void
  onCancel: () => void
}

const MAX_HEADERS = 10
const inputClass =
  'w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-900 focus:outline-none disabled:bg-slate-100'

/** S-04 모니터 등록, S-06 모니터 수정의 입력 폼 */
export function MonitorForm({
  initialValues,
  savedHeaderNames = [],
  serverErrors,
  submitting,
  submitLabel,
  onSubmit,
  onCancel,
}: MonitorFormProps) {
  const [values, setValues] = useState(initialValues)
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({})
  const errors = { ...serverErrors, ...clientErrors }

  function update<K extends keyof MonitorFormValues>(key: K, value: MonitorFormValues[K]) {
    setValues((prev) => ({ ...prev, [key]: value }))
  }

  function updateHeader(index: number, patch: Partial<MonitorHeader>) {
    update(
      'headers',
      values.headers.map((header, i) => (i === index ? { ...header, ...patch } : header)),
    )
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const found = validateForm(values)
    setClientErrors(found)
    if (Object.keys(found).length === 0) onSubmit(values)
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="mt-6 space-y-5 rounded-lg border border-slate-200 bg-white p-6">
      {errors.form && (
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800">
          {errors.form}
        </p>
      )}

      <Field label="이름" required error={errors.name} htmlFor="monitor-name">
        <input
          id="monitor-name"
          className={inputClass}
          maxLength={100}
          value={values.name}
          onChange={(e) => update('name', e.target.value)}
          placeholder="결제 API"
        />
      </Field>

      <Field label="URL" required error={errors.url} htmlFor="monitor-url">
        <input
          id="monitor-url"
          className={inputClass}
          type="url"
          maxLength={2048}
          value={values.url}
          onChange={(e) => update('url', e.target.value)}
          placeholder="https://api.example.com/health"
        />
      </Field>

      <div className="grid gap-5 sm:grid-cols-3">
        <Field label="메서드" error={errors.method} htmlFor="monitor-method">
          <select
            id="monitor-method"
            className={inputClass}
            value={values.method}
            onChange={(e) => update('method', e.target.value as HttpMethod)}
          >
            <option value="GET">GET</option>
            <option value="HEAD">HEAD</option>
          </select>
        </Field>

        <Field label="검사 주기" required error={errors.intervalMinutes} htmlFor="monitor-interval">
          <select
            id="monitor-interval"
            className={inputClass}
            value={values.intervalMinutes}
            onChange={(e) => update('intervalMinutes', Number(e.target.value))}
          >
            {INTERVAL_OPTIONS.map((minutes) => (
              <option key={minutes} value={minutes}>
                {minutes}분
              </option>
            ))}
          </select>
        </Field>

        <Field label="Timeout(초)" error={errors.timeoutSeconds} htmlFor="monitor-timeout">
          <input
            id="monitor-timeout"
            className={inputClass}
            type="number"
            min={1}
            max={30}
            value={values.timeoutSeconds}
            onChange={(e) => update('timeoutSeconds', e.target.value)}
          />
        </Field>
      </div>

      <Field
        label="기대 상태 코드"
        error={errors.expectedStatusCodes}
        htmlFor="monitor-codes"
        hint="쉼표로 구분해 최대 5개. 비워 두면 2xx 전체를 정상으로 봅니다."
      >
        <input
          id="monitor-codes"
          className={inputClass}
          value={values.expectedStatusCodes}
          onChange={(e) => update('expectedStatusCodes', e.target.value)}
          placeholder="비워 두면 2xx"
        />
      </Field>

      <fieldset>
        <legend className="text-sm font-medium">요청 헤더</legend>
        <p className="mt-1 text-xs text-slate-500">인증 토큰 등은 암호화해 저장하며, 저장한 뒤에는 값을 다시 보여 주지 않습니다.</p>

        {!values.headersEdited ? (
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <span className="text-sm text-slate-600">
              {savedHeaderNames.length > 0 ? `저장된 헤더: ${savedHeaderNames.join(', ')}` : '저장된 헤더가 없습니다.'}
            </span>
            <button
              type="button"
              onClick={() => setValues((prev) => ({ ...prev, headersEdited: true, headers: [] }))}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
            >
              헤더 다시 입력
            </button>
          </div>
        ) : (
          <div className="mt-3 space-y-2">
            {values.headers.map((header, index) => (
              <div key={index}>
                <div className="flex gap-2">
                  <input
                    aria-label={`헤더 ${index + 1} 이름`}
                    className={inputClass}
                    value={header.name}
                    onChange={(e) => updateHeader(index, { name: e.target.value })}
                    placeholder="Authorization"
                  />
                  <input
                    aria-label={`헤더 ${index + 1} 값`}
                    className={inputClass}
                    type="password"
                    autoComplete="off"
                    value={header.value}
                    onChange={(e) => updateHeader(index, { value: e.target.value })}
                    placeholder="값"
                  />
                  <button
                    type="button"
                    aria-label={`헤더 ${index + 1} 삭제`}
                    onClick={() => update('headers', values.headers.filter((_, i) => i !== index))}
                    className="shrink-0 rounded-md border border-slate-300 px-3 text-sm hover:bg-slate-50"
                  >
                    삭제
                  </button>
                </div>
                <FieldError message={errors[`headers[${index}].name`] ?? errors[`headers[${index}].value`]} />
              </div>
            ))}
            <FieldError message={errors.headers} />
            {values.headers.length < MAX_HEADERS && (
              <button
                type="button"
                onClick={() => update('headers', [...values.headers, { name: '', value: '' }])}
                className="rounded-md border border-dashed border-slate-300 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50"
              >
                + 헤더 추가
              </button>
            )}
          </div>
        )}
      </fieldset>

      <div className="flex justify-end gap-2 border-t border-slate-100 pt-5">
        <button type="button" onClick={onCancel} className="rounded-md px-4 py-2 text-sm hover:bg-slate-100">
          취소
        </button>
        <button
          type="submit"
          disabled={submitting}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
        >
          {submitting ? '저장 중...' : submitLabel}
        </button>
      </div>
    </form>
  )
}

function Field({
  label,
  required,
  error,
  hint,
  htmlFor,
  children,
}: {
  label: string
  required?: boolean
  error?: string
  hint?: string
  htmlFor: string
  children: ReactNode
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1 block text-sm font-medium">
        {label}
        {required && <span className="ml-0.5 text-red-600">*</span>}
      </label>
      {children}
      {hint && !error && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
      <FieldError message={error} />
    </div>
  )
}

function FieldError({ message }: { message?: string }) {
  if (!message) return null
  return <p className="mt-1 text-xs text-red-700">{message}</p>
}
