/**
 * 환경 차이를 **여기 한 곳**에만 둔다.
 *
 * local 은 dev proxy 로 same-origin 이라 base 가 빈 문자열이고, alpha 는 콘솔과 게이트웨이가
 * 다른 호스트라 절대 origin 이다. 코드의 나머지는 이 값만 본다.
 */
const RAW_BASE = import.meta.env.VITE_API_BASE_URL ?? ''

/** 끝 슬래시를 지운다 — `${base}/api/...` 조합에서 `//` 가 생기면 라우트 매칭이 어긋난다. */
export const apiBaseUrl = RAW_BASE.replace(/\/+$/, '')

/** cross-origin 배치인가. 로그인 이동·쿠키 취급이 갈리는 분기점이라 이름을 붙여 둔다. */
export const isCrossOrigin = apiBaseUrl.length > 0

/**
 * 서버가 준 경로를 **브라우저가 이동할 수 있는 주소**로 만든다.
 *
 * ⚠️ 401 응답의 `loginUrl` 은 `/oauth2/authorization/keycloak` 같은 **상대경로**다.
 * same-origin 에서는 그대로 써도 되지만, cross-origin 에서 그대로 쓰면 **FE 호스트로 이동**해
 * 404 가 난다 — 게이트웨이에만 있는 경로이기 때문이다. 놓치기 쉬운 자리라 함수로 고정한다.
 */
export function toAbsolute(path: string): string {
  if (/^https?:\/\//.test(path)) return path
  return `${apiBaseUrl}${path}`
}
