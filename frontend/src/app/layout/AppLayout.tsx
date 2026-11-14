import { Link, NavLink, Outlet } from 'react-router'

const navItems = [
  { to: '/dashboard', label: '대시보드' },
  { to: '/incidents', label: '장애 이력' },
  { to: '/settings', label: '설정' },
]

/** 랜딩과 로그인 처리를 제외한 화면의 공통 레이아웃 */
export function AppLayout() {
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
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
