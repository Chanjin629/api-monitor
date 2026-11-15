import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router'
import { refreshAccessToken } from '../shared/api/client'

/**
 * S-02 로그인 처리. GitHub 로그인 후 Backend가 Refresh Token 쿠키를 설정하고 이 화면으로 보낸다.
 * 쿠키로 Access Token을 받아 대시보드로 이동한다.
 */
export function AuthCallbackPage() {
  const navigate = useNavigate()
  const started = useRef(false)

  useEffect(() => {
    // 개발 모드(StrictMode)에서 effect가 두 번 실행되어도 한 번만 처리한다.
    if (started.current) return
    started.current = true
    refreshAccessToken().then((ok) => {
      navigate(ok ? '/dashboard' : '/?loginError=true', { replace: true })
    })
  }, [navigate])

  return (
    <main className="flex min-h-screen items-center justify-center">
      <p className="text-slate-600">로그인 중입니다...</p>
    </main>
  )
}
