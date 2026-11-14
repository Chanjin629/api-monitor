/** S-01 랜딩. 로그인과 데모 버튼은 9단계에서 동작을 연결한다. */
export function LandingPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col items-center justify-center px-4 text-center">
      <p className="text-sm font-semibold tracking-wide text-slate-500">API Monitor</p>
      <h1 className="mt-3 text-3xl font-bold sm:text-4xl">내 API 장애를 사용자보다 먼저 알아채세요</h1>
      <p className="mt-4 text-slate-600">
        API를 등록하면 주기적으로 검사하고, 장애와 복구를 Discord로 알려 드립니다.
      </p>
      <div className="mt-8 flex flex-wrap justify-center gap-3">
        <button
          type="button"
          disabled
          className="rounded-md bg-slate-900 px-5 py-3 text-sm font-semibold text-white disabled:opacity-50"
        >
          GitHub로 시작하기
        </button>
        <button
          type="button"
          disabled
          className="rounded-md border border-slate-300 bg-white px-5 py-3 text-sm font-semibold disabled:opacity-50"
        >
          데모 체험하기
        </button>
      </div>
      <p className="mt-4 text-xs text-slate-400">로그인은 9단계에서 연결됩니다.</p>
    </main>
  )
}
