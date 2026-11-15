import { useQuery } from '@tanstack/react-query'
import { fetchMe } from './authApi'

export const meQueryKey = ['me'] as const

/** 로그인한 사용자 정보 */
export function useMe() {
  return useQuery({ queryKey: meQueryKey, queryFn: fetchMe, staleTime: 5 * 60 * 1000 })
}
