import { toAbsolute } from './env'

/**
 * CSRF 토큰을 확보한다. **출처가 배포 형태에 따라 갈린다.**
 *
 * | 배포 | 토큰 출처 | 이유 |
 * |---|---|---|
 * | same-origin (로컬 dev proxy) | `XSRF-TOKEN` 쿠키 | 게이트웨이와 같은 호스트라 JS 가 읽는다 |
 * | **cross-origin (alpha)** | **`GET /csrf`** | 쿠키에 `Domain` 이 없어 **host-only** — FE 는 못 읽는다 |
 *
 * ## 왜 쿠키만으로는 안 되는가
 * 게이트웨이가 내리는 쿠키는 이렇다.
 *
 * ```
 * set-cookie: XSRF-TOKEN=<value>; Path=/          ← Domain 없음
 * set-cookie: SESSION=<value>; ... SameSite=Lax
 * ```
 *
 * `SESSION` 은 잘 실린다. SameSite 는 **site**(등록가능 도메인) 기준이라 형제 호스트끼리는
 * 통과하기 때문이다. 반면 `document.cookie` 는 **host** 기준이라 다른 호스트의 쿠키는 목록에
 * 아예 없다. 그래서 **GET 은 전부 200 인데 POST 만 403** 이라는 헷갈리는 형태가 된다 —
 * 인증 문제로 보이지만 인증은 멀쩡하다.
 *
 * 세 조각(쿠키 저장소 · XOR 핸들러 해제 · 구독 강제)이 서버에 다 갖춰져 있어도 이건 별개로
 * 터진다(CLAUDE.md §6.1). 상세와 실측은 `docs/learning/26-bff-spa-integration.md` §5.2.
 */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'
export const CSRF_PARAM = '_csrf'

export interface CsrfToken {
  token: string
  headerName: string
  parameterName: string
}

/** same-origin 배포에서만 값이 나온다. cross-origin 이면 항상 null 이다. */
export function readCsrfCookie(): string | null {
  const raw = document.cookie
    .split('; ')
    .find((c) => c.startsWith(`${CSRF_COOKIE}=`))
    ?.split('=')[1]
  return raw ? decodeURIComponent(raw) : null
}

let cached: CsrfToken | null = null
/** 동시에 여러 요청이 토큰을 찾을 때 `/csrf` 를 N번 부르지 않게 한다. */
let inflight: Promise<CsrfToken | null> | null = null

async function fetchToken(): Promise<CsrfToken | null> {
  try {
    // ⚠️ credentials 가 없으면 **세션과 짝이 맞지 않는** 토큰을 받아 결국 403 이 된다.
    const res = await fetch(toAbsolute('/csrf'), {
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
    // CSRF 가 꺼진 구성이면 204 다. 본문이 없으므로 json() 을 부르면 던진다.
    if (!res.ok || res.status === 204) return null
    cached = (await res.json()) as CsrfToken
    return cached
  } catch {
    // 네트워크·CORS 실패. 여기서 던지면 화면 전체가 죽으므로 null 로 내려보내고
    // 호출부가 "토큰 없음" 으로 다루게 한다(버튼 비활성 등).
    return null
  }
}

/**
 * 헤더 이름과 값을 **함께** 돌려준다.
 *
 * 이름을 클라이언트가 하드코딩하지 않는 이유는, 서버가 이름을 바꿨을 때 그 어긋남이
 * **403 하나로만** 드러나기 때문이다. 서버가 알려주면 그 실패가 성립할 자리가 없다.
 */
export async function ensureCsrfToken(): Promise<CsrfToken | null> {
  const fromCookie = readCsrfCookie()
  if (fromCookie) {
    return { token: fromCookie, headerName: CSRF_HEADER, parameterName: CSRF_PARAM }
  }
  if (cached) return cached
  if (!inflight) {
    inflight = fetchToken().finally(() => {
      inflight = null
    })
  }
  return inflight
}

/**
 * 캐시를 버린다. 403 을 만났을 때 부른다.
 *
 * 토큰은 세션에 묶이므로 로그아웃·재로그인 뒤에는 캐시된 값이 죽는다. 버리지 않으면
 * **한 번 403 이 난 뒤 새로고침 전까지 계속 403** 이다.
 */
export function invalidateCsrfToken(): void {
  cached = null
}
