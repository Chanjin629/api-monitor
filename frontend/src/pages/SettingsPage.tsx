import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { logout, withdraw } from '../features/auth/authApi'
import { useMe } from '../features/auth/useMe'
import {
  CHANNEL_LIMIT,
  channelErrorMessage,
  channelKeys,
  createChannel,
  deleteChannel,
  fetchChannels,
  isDiscordWebhookUrl,
  sendTestMessage,
  updateChannel,
  type Channel,
} from '../features/notifications/channelApi'

const inputClass =
  'w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-900 focus:outline-none'
const buttonClass = 'rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-50'

/** S-08 설정: 알림 채널과 계정 */
export function SettingsPage() {
  return (
    <section className="max-w-3xl space-y-10">
      <h1 className="text-2xl font-bold">설정</h1>
      <ChannelSection />
      <AccountSection />
    </section>
  )
}

function ChannelSection() {
  const { data: me } = useMe()
  const isDemo = me?.role === 'DEMO'
  const queryClient = useQueryClient()
  const [adding, setAdding] = useState(false)
  const [notice, setNotice] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const { data, isPending, isError, error } = useQuery({ queryKey: channelKeys.all, queryFn: fetchChannels })
  const channels = data?.items ?? []

  const refresh = () => queryClient.invalidateQueries({ queryKey: channelKeys.all })
  const showError = (e: unknown) => setNotice({ kind: 'error', text: channelErrorMessage(e) })

  const toggle = useMutation({
    mutationFn: (channel: Channel) => updateChannel(channel.id, { enabled: !channel.enabled }),
    onSuccess: refresh,
    onError: showError,
  })
  const remove = useMutation({ mutationFn: deleteChannel, onSuccess: refresh, onError: showError })
  const test = useMutation({
    mutationFn: sendTestMessage,
    onSuccess: () => setNotice({ kind: 'ok', text: '테스트 메시지를 보냈습니다. Discord 채널을 확인해 주세요.' }),
    onError: showError,
  })

  function handleDelete(channel: Channel) {
    if (window.confirm(`"${channel.name}" 채널을 삭제할까요?\n보내지 못한 알림도 함께 삭제됩니다.`)) {
      remove.mutate(channel.id)
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-lg font-semibold">알림 채널</h2>
        <span className="text-sm text-slate-500">
          장애가 시작되거나 복구되면 모든 켜진 채널로 한 번씩 알립니다. ({channels.length} / {CHANNEL_LIMIT})
        </span>
        {!isDemo && !adding && (
          <button
            type="button"
            disabled={channels.length >= CHANNEL_LIMIT}
            onClick={() => setAdding(true)}
            className="ml-auto rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
          >
            + 채널 추가
          </button>
        )}
      </div>

      {notice && (
        <p
          role={notice.kind === 'error' ? 'alert' : 'status'}
          className={`mt-3 rounded-md px-3 py-2 text-sm ${
            notice.kind === 'error' ? 'bg-red-50 text-red-800' : 'bg-green-50 text-green-800'
          }`}
        >
          {notice.text}
        </p>
      )}

      {adding && (
        <ChannelForm
          submitLabel="추가"
          onCancel={() => setAdding(false)}
          onSubmit={async (values) => {
            await createChannel(values)
            setAdding(false)
            setNotice({ kind: 'ok', text: '채널을 추가했습니다. 테스트 발송으로 메시지가 오는지 확인해 보세요.' })
            await refresh()
          }}
        />
      )}

      {isPending && <div className="mt-3 h-20 animate-pulse rounded-lg bg-slate-200" />}
      {isError && <p className="mt-3 text-sm text-red-700">{error.message}</p>}
      {data && channels.length === 0 && !adding && (
        <p className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-slate-600">
          알림 채널을 등록해야 장애 알림을 받을 수 있습니다.
        </p>
      )}

      <ul className="mt-3 divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white">
        {channels.map((channel) => (
          <ChannelRow
            key={channel.id}
            channel={channel}
            readOnly={isDemo}
            busy={toggle.isPending || remove.isPending || test.isPending}
            onToggle={() => toggle.mutate(channel)}
            onTest={() => {
              setNotice(null)
              test.mutate(channel.id)
            }}
            onDelete={() => handleDelete(channel)}
            onSaved={async () => {
              setNotice({ kind: 'ok', text: '채널을 수정했습니다.' })
              await refresh()
            }}
          />
        ))}
      </ul>
    </div>
  )
}

function ChannelRow({
  channel,
  readOnly,
  busy,
  onToggle,
  onTest,
  onDelete,
  onSaved,
}: {
  channel: Channel
  readOnly: boolean
  busy: boolean
  onToggle: () => void
  onTest: () => void
  onDelete: () => void
  onSaved: () => Promise<void>
}) {
  const [editing, setEditing] = useState(false)

  if (editing) {
    return (
      <li className="p-4">
        <ChannelForm
          initialName={channel.name}
          urlOptional
          submitLabel="저장"
          onCancel={() => setEditing(false)}
          onSubmit={async (values) => {
            await updateChannel(channel.id, values)
            setEditing(false)
            await onSaved()
          }}
        />
      </li>
    )
  }

  return (
    <li className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm">
      <span className="font-medium">{channel.name}</span>
      <span className="text-slate-500">Discord</span>
      <span className="font-mono text-slate-500">{channel.webhookUrlMasked}</span>
      {readOnly ? (
        <span className="ml-auto text-slate-500">{channel.enabled ? '켜짐' : '꺼짐'}</span>
      ) : (
        <div className="ml-auto flex gap-2">
          <button
            type="button"
            aria-pressed={channel.enabled}
            disabled={busy}
            onClick={onToggle}
            className={`rounded-full px-3 py-1 text-xs font-medium disabled:opacity-50 ${
              channel.enabled ? 'bg-green-100 text-green-800' : 'bg-slate-100 text-slate-600'
            }`}
          >
            {channel.enabled ? '켜짐' : '꺼짐'}
          </button>
          <button type="button" disabled={busy} onClick={onTest} className={buttonClass}>
            테스트
          </button>
          <button type="button" onClick={() => setEditing(true)} className={buttonClass}>
            수정
          </button>
          <button type="button" disabled={busy} onClick={onDelete} className={`${buttonClass} text-red-700`}>
            삭제
          </button>
        </div>
      )}
    </li>
  )
}

/** 채널 추가·수정 폼. 수정할 때 주소를 비워 두면 기존 주소를 유지한다. */
function ChannelForm({
  initialName = '',
  urlOptional = false,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initialName?: string
  urlOptional?: boolean
  submitLabel: string
  onSubmit: (values: { name: string; webhookUrl?: string }) => Promise<void>
  onCancel: () => void
}) {
  const [name, setName] = useState(initialName)
  const [webhookUrl, setWebhookUrl] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (name.trim() === '') return setError('채널 이름을 입력해 주세요.')
    const urlEntered = webhookUrl.trim() !== ''
    if ((!urlOptional || urlEntered) && !isDiscordWebhookUrl(webhookUrl)) return setError('Discord Webhook 주소를 입력해 주세요')

    setError(null)
    setSubmitting(true)
    try {
      await onSubmit({ name: name.trim(), ...(urlEntered ? { webhookUrl: webhookUrl.trim() } : {}) })
    } catch (e) {
      setError(channelErrorMessage(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="mt-3 space-y-3 rounded-lg border border-slate-200 bg-white p-4">
      {error && (
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800">
          {error}
        </p>
      )}
      <label className="block text-sm">
        <span className="mb-1 block font-medium">채널 이름</span>
        <input className={inputClass} maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="운영 알림" />
      </label>
      <label className="block text-sm">
        <span className="mb-1 block font-medium">Discord Webhook 주소</span>
        <input
          className={inputClass}
          // Webhook 주소는 비밀번호처럼 다룬다. 화면에 그대로 보이지 않게 한다.
          type="password"
          autoComplete="off"
          value={webhookUrl}
          onChange={(e) => setWebhookUrl(e.target.value)}
          placeholder={urlOptional ? '바꾸지 않으려면 비워 두세요' : 'https://discord.com/api/webhooks/...'}
        />
        <span className="mt-1 block text-xs text-slate-500">
          Discord 채널 설정 → 연동 → 웹후크에서 만든 주소입니다. 암호화해 저장하며 다시 보여 주지 않습니다.
        </span>
      </label>
      <div className="flex justify-end gap-2">
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

function AccountSection() {
  const { data: me } = useMe()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [confirming, setConfirming] = useState(false)
  const [typedName, setTypedName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const isDemo = me?.role === 'DEMO'

  async function leave(action: () => Promise<void>) {
    try {
      await action()
      queryClient.clear()
      navigate('/', { replace: true })
    } catch (e) {
      setError(e instanceof Error ? e.message : '요청을 처리하지 못했습니다.')
    }
  }

  return (
    <div>
      <h2 className="text-lg font-semibold">계정</h2>
      <div className="mt-3 space-y-4 rounded-lg border border-slate-200 bg-white p-4 text-sm">
        <p>
          <span className="font-medium">{me?.username ?? '...'}</span>
          <span className="ml-2 text-slate-500">{isDemo ? '(데모 계정)' : '(GitHub)'}</span>
        </p>
        {error && (
          <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-red-800">
            {error}
          </p>
        )}
        {!isDemo && (
          <div className="flex flex-wrap gap-2">
            <button type="button" onClick={() => leave(() => logout({ allDevices: true }))} className={buttonClass}>
              모든 기기 로그아웃
            </button>
            <button
              type="button"
              onClick={() => setConfirming(true)}
              className="ml-auto rounded-md border border-red-300 bg-white px-3 py-1.5 text-sm text-red-700 hover:bg-red-50"
            >
              회원 탈퇴
            </button>
          </div>
        )}
        {confirming && me && (
          <div className="rounded-md border border-red-200 bg-red-50 p-4">
            <p className="text-red-900">
              모니터, 장애 이력, 알림 채널이 모두 삭제되며 되돌릴 수 없습니다. 계속하려면 GitHub 사용자 이름(
              <span className="font-mono">{me.username}</span>)을 입력해 주세요.
            </p>
            <input
              aria-label="GitHub 사용자 이름 확인"
              className={`${inputClass} mt-3`}
              value={typedName}
              onChange={(e) => setTypedName(e.target.value)}
            />
            <div className="mt-3 flex justify-end gap-2">
              <button type="button" onClick={() => setConfirming(false)} className="rounded-md px-4 py-2 hover:bg-white">
                취소
              </button>
              <button
                type="button"
                disabled={typedName !== me.username}
                onClick={() => leave(withdraw)}
                className="rounded-md bg-red-700 px-4 py-2 font-medium text-white hover:bg-red-800 disabled:opacity-50"
              >
                탈퇴
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
