/**
 * 환경 차이를 **여기 한 곳**에만 둔다.
 *
 * local 은 dev proxy 로 same-origin 이라 base 가 빈 문자열이고, alpha 는 콘솔과 게이트웨이가
 * 다른 호스트라 절대 origin 이다. 코드의 나머지는 이 값만 본다.
 *
 * ## 왜 값의 출처가 둘인가 (런타임 → 빌드타임 순)
 *
 * | 출처 | 시점 | 쓰이는 곳 |
 * |---|---|---|
 * | `window.__UNIGATE_CONFIG__` (`/config.js`) | **컨테이너 기동 시** envsubst 로 생성 | 배포 |
 * | `import.meta.env.VITE_API_BASE_URL` | **빌드 시** 번들에 박힘 | 로컬 dev |
 *
 * 빌드타임만 쓰면 게이트웨이 호스트가 이미지에 박혀 **환경마다 이미지를 다시 구워야 한다.**
 * 같은 이미지를 alpha 에도 다른 환경에도 올리려면 값이 런타임에 들어와야 한다.
 * 그래서 런타임 값을 우선하되, **비어 있으면** 빌드타임 값으로 내려간다 —
 * `npm run dev:alpha` 처럼 dev 서버에서 cross-origin 을 재현하는 경로가 살아 있어야 하기 때문이다.
 */
const RUNTIME_BASE = window.__UNIGATE_CONFIG__?.apiBaseUrl ?? ''
const RAW_BASE = RUNTIME_BASE || (import.meta.env.VITE_API_BASE_URL ?? '')

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
