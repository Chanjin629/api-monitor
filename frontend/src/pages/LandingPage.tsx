import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { demoLogin, startGithubLogin } from '../features/auth/authApi'
import { ApiError } from '../shared/api/ApiError'

/** S-01 랜딩 */
export function LandingPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [demoError, setDemoError] = useState<string | null>(null)
  const [demoLoading, setDemoLoading] = useState(false)
  const loginFailed = searchParams.get('loginError') === 'true'

  async function handleDemo() {
    setDemoError(null)
    setDemoLoading(true)
    try {
      await demoLogin()
      navigate('/dashboard')
    } catch (error) {
      setDemoError(error instanceof ApiError ? error.message : '데모를 시작하지 못했습니다.')
    } finally {
      setDemoLoading(false)
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col items-center justify-center px-4 text-center">
      <p className="text-sm font-semibold tracking-wide text-slate-500">API Monitor</p>
      <h1 className="mt-3 text-3xl font-bold sm:text-4xl">내 API 장애를 사용자보다 먼저 알아채세요</h1>
      <p className="mt-4 text-slate-600">
        API를 등록하면 주기적으로 검사하고, 장애와 복구를 Discord로 알려 드립니다.
      </p>

      {loginFailed && (
        <p role="alert" className="mt-6 rounded-md bg-red-50 px-4 py-2 text-sm text-red-700">
          로그인에 실패했습니다. 다시 시도해 주세요.
        </p>
      )}

      <div className="mt-8 flex flex-wrap justify-center gap-3">
        <button
          type="button"
          onClick={startGithubLogin}
          className="rounded-md bg-slate-900 px-5 py-3 text-sm font-semibold text-white hover:bg-slate-700"
        >
          GitHub로 시작하기
        </button>
        <button
          type="button"
          onClick={handleDemo}
          disabled={demoLoading}
          className="rounded-md border border-slate-300 bg-white px-5 py-3 text-sm font-semibold hover:bg-slate-100 disabled:opacity-50"
        >
          {demoLoading ? '데모 준비 중...' : '데모 체험하기'}
        </button>
      </div>
      {demoError && <p className="mt-4 text-sm text-red-600">{demoError}</p>}

      <div className="mt-12 grid gap-4 text-sm text-slate-600 sm:grid-cols-3">
        <p>1분 주기 검사</p>
        <p>장애·복구 Discord 알림</p>
        <p>응답 시간 그래프</p>
      </div>
    </main>
  )
}
