type PagePlaceholderProps = {
  title: string
  /** 이 화면을 구현하는 단계 */
  phase: string
  description?: string
}

/** 아직 구현하지 않은 화면에 표시하는 임시 내용 */
export function PagePlaceholder({ title, phase, description }: PagePlaceholderProps) {
  return (
    <section>
      <h1 className="text-2xl font-bold">{title}</h1>
      {description && <p className="mt-2 text-slate-600">{description}</p>}
      <p className="mt-6 rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">
        {phase}에서 구현할 화면입니다.
      </p>
    </section>
  )
}
