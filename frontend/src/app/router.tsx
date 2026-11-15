import { createBrowserRouter } from 'react-router'
import { AppLayout } from './layout/AppLayout'
import { RequireAuth } from './RequireAuth'
import { AuthCallbackPage } from '../pages/AuthCallbackPage'
import { DashboardPage } from '../pages/DashboardPage'
import { IncidentsPage } from '../pages/IncidentsPage'
import { LandingPage } from '../pages/LandingPage'
import { MonitorDetailPage } from '../pages/MonitorDetailPage'
import { MonitorFormPage } from '../pages/MonitorFormPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { SettingsPage } from '../pages/SettingsPage'

/**
 * 화면 경로. 화면 목록은 docs/06-screens.md를 따른다.
 * 공통 레이아웃 안의 화면은 모두 로그인이 필요하다.
 */
export const router = createBrowserRouter([
  { path: '/', element: <LandingPage /> },
  { path: '/auth/callback', element: <AuthCallbackPage /> },
  {
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { path: '/dashboard', element: <DashboardPage /> },
      { path: '/monitors/new', element: <MonitorFormPage mode="create" /> },
      { path: '/monitors/:id', element: <MonitorDetailPage /> },
      { path: '/monitors/:id/edit', element: <MonitorFormPage mode="edit" /> },
      { path: '/incidents', element: <IncidentsPage /> },
      { path: '/settings', element: <SettingsPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])
