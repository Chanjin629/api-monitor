import { useQueryClient } from '@tanstack/react-query'
import { Link, NavLink, Outlet, useNavigate } from 'react-router'
import { logout, startGithubLogin } from '../../features/auth/authApi'
import { useMe } from '../../features/auth/useMe'

const navItems = [
  { to: '/dashboard', label: '대시보드' },
  { to: '/incidents', label: '장애 이력' },
  { to: '/settings', label: '설정' },
]

/** 랜딩과 로그인 처리를 제외한 화면의 공통 레이아웃 */
export function AppLayout() {
  const { data: me } = useMe()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const isDemo = me?.role === 'DEMO'

  async function handleLogout(allDevices: boolean) {
    try {
      await logout({ allDevices })
    } finally {
      queryClient.clear()
      navigate('/', { replace: true })
    }
  }

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-8 px-4">
          <Link to="/dashboard" className="text-lg font-bold tracking-tight">
            API Monitor
          </Link>
          <nav aria-label="주요 메뉴" className="flex gap-1">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  `rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                    isActive ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'
                  }`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <details className="relative ml-auto">
            <summary className="flex cursor-pointer list-none items-center gap-2 rounded-md px-2 py-1 text-sm hover:bg-slate-100">
              {me?.avatarUrl && <img src={me.avatarUrl} alt="" className="h-7 w-7 rounded-full" />}
              <span className="font-medium">{me?.displayName ?? me?.username ?? '...'}</span>
            </summary>
            <div className="absolute right-0 z-10 mt-2 w-48 rounded-md border border-slate-200 bg-white py-1 shadow-lg">
              <button
                type="button"
                onClick={() => handleLogout(false)}
                className="block w-full px-4 py-2 text-left text-sm hover:bg-slate-50"
              >
                로그아웃
              </button>
              {!isDemo && (
                <button
                  type="button"
                  onClick={() => handleLogout(true)}
                  className="block w-full px-4 py-2 text-left text-sm hover:bg-slate-50"
                >
                  모든 기기 로그아웃
                </button>
              )}
            </div>
          </details>
        </div>
      </header>

      {isDemo && (
        <div className="bg-amber-50 text-amber-900">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-2 text-sm">
            <span>읽기 전용 체험 중입니다. 변경 기능은 사용할 수 없습니다.</span>
            <button type="button" onClick={startGithubLogin} className="font-semibold underline underline-offset-4">
              GitHub로 시작하기
            </button>
          </div>
        </div>
      )}

      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
