import { useEffect, useState, type ReactNode } from 'react'
import { Navigate } from 'react-router'
import { refreshAccessToken } from '../shared/api/client'
import { tokenStore } from '../shared/api/tokenStore'

type Status = 'checking' | 'allowed' | 'denied'

/**
 * 로그인이 필요한 화면을 보호한다.
 * 새로고침으로 메모리의 Access Token이 사라졌다면 Refresh Token 쿠키로 다시 받아 본다.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>(() => (tokenStore.get() ? 'allowed' : 'checking'))

  useEffect(() => {
    if (status !== 'checking') return
    let cancelled = false
    refreshAccessToken().then((ok) => {
      if (!cancelled) setStatus(ok ? 'allowed' : 'denied')
    })
    return () => {
      cancelled = true
    }
  }, [status])

  if (status === 'denied') return <Navigate to="/" replace />
  if (status === 'checking') {
    return <p className="p-8 text-center text-sm text-slate-500">로그인 정보를 확인하는 중입니다...</p>
  }
  return children
}
