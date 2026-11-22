/** 날짜, 시간, 숫자를 화면 문구로 바꾸는 공통 함수. 시각은 사용자의 브라우저 시간대로 보여 준다. */

/** "방금 전", "32초 전", "5분 전", "3시간 전", "2일 전" */
export function formatRelative(iso: string | null, now: number = Date.now()): string {
  if (!iso) return '-'
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  if (seconds < 10) return '방금 전'
  if (seconds < 60) return `${seconds}초 전`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}시간 전`
  return `${Math.floor(seconds / 86400)}일 전`
}

/** "1분 미만", "20분", "1시간 5분", "1일 3시간". 서버의 Discord 메시지와 같은 규칙이다. */
export function formatDuration(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  if (minutes < 1) return '1분 미만'
  if (minutes < 60) return `${minutes}분`
  if (minutes < 60 * 24) return joinParts(Math.floor(minutes / 60), '시간', minutes % 60, '분')
  return joinParts(Math.floor(minutes / (60 * 24)), '일', Math.floor(minutes / 60) % 24, '시간')
}

function joinParts(major: number, majorUnit: string, minor: number, minorUnit: string) {
  return minor === 0 ? `${major}${majorUnit}` : `${major}${majorUnit} ${minor}${minorUnit}`
}

/** 진행 중인 장애의 지속 시간. "12분째" */
export function formatOngoing(startedAt: string, now: number = Date.now()): string {
  return `${formatDuration((now - new Date(startedAt).getTime()) / 1000)}째`
}

/** "11/09 20:00" */
export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** "20:45:00" */
export function formatTime(iso: string): string {
  const date = new Date(iso)
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** "99.86%". 값이 없으면 "-" */
export function formatPercent(value: number | null | undefined): string {
  return value == null ? '-' : `${value.toFixed(2)}%`
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}
