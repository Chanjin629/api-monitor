import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiError } from '../shared/api/ApiError'

/**
 * 서버 데이터 캐시 설정.
 * 4xx 오류는 다시 요청해도 결과가 같으므로 재시도하지 않고, 네트워크 오류와 5xx만 2번까지 재시도한다.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
        return failureCount < 2
      },
    },
  },
})

export function AppProviders({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
