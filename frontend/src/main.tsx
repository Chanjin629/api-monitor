import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from './app/providers'
import { queryClient } from './app/queryClient'
import { router } from './app/router'
import { setSessionExpiredHandler } from './shared/api/client'
import './index.css'

// 재발급까지 실패하면 캐시를 비우고 랜딩으로 보낸다.
setSessionExpiredHandler(() => {
  queryClient.clear()
  void router.navigate('/', { replace: true })
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
