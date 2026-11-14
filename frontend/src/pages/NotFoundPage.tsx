import { Link } from 'react-router'

/** 존재하지 않는 주소 */
export function NotFoundPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 px-4 text-center">
      <h1 className="text-2xl font-bold">페이지를 찾을 수 없습니다</h1>
      <Link to="/" className="text-sm font-medium text-slate-600 underline underline-offset-4">
        처음으로 돌아가기
      </Link>
    </main>
  )
}
