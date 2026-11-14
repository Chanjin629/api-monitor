import { createBrowserRouter } from 'react-router'
import { AppLayout } from './layout/AppLayout'
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
 * 로그인이 필요한 경로의 보호는 9단계(GitHub 로그인)에서 추가한다.
 */
export const router = createBrowserRouter([
  { path: '/', element: <LandingPage /> },
  { path: '/auth/callback', element: <AuthCallbackPage /> },
  {
    element: <AppLayout />,
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
