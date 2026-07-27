/**
 * CSRF 토큰은 **쿠키에서 읽어 헤더로 돌려보낸다**(double submit).
 *
 * 게이트웨이가 `XSRF-TOKEN` 쿠키를 HttpOnly 없이 내려주는 이유가 이것이다 —
 * JS 가 읽어야 헤더에 실을 수 있다. 세 조각(쿠키 저장소 · XOR 핸들러 해제 · 구독 강제)이
 * 모두 맞아야 동작하며, 하나라도 빠지면 증상이 **403 하나로 같다**(CLAUDE.md §6.1).
 */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

export function readCsrfToken(): string | null {
  const raw = document.cookie
    .split('; ')
    .find((c) => c.startsWith(`${CSRF_COOKIE}=`))
    ?.split('=')[1]
  return raw ? decodeURIComponent(raw) : null
}
