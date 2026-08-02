import { classifyRoute, recordRequest } from '../inspect/requestLog'
import { ensureCsrfToken, invalidateCsrfToken } from './csrf'
import { toAbsolute } from './env'
import { ApiError, parseProblem } from './problem'

/**
 * 모든 요청이 지나는 **단 하나의 문**.
 *
 * 401 분기·CSRF 헤더·테넌트 헤더가 페이지마다 흩어지면 "어디서 빠뜨렸지" 를 추적할 수 없다.
 * 서버 쪽에서 `anyRequest` 규칙으로 default-deny 를 만든 것과 같은 판단이다
 * (`docs/learning/24`) — **잊을 수 있는 자리를 없앤다.**
 */
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** 클라이언트의 테넌트 **주장**. 검증은 게이트가 한다(Phase 9f). */
  tenant?: string
  /** 위조 실험용 원시 헤더. 일반 화면에서는 쓰지 않는다. */
  rawHeaders?: Record<string, string>
  /**
   * CSRF 토큰을 붙이지 않는다 — **미인증 상태에서 부르는 공개 POST 전용**(가입).
   *
   * 서버가 `/iam/register` 를 CSRF 매처에서 제외했기 때문에 토큰이 필요 없다. 근거는
   * `SecurityConfig.csrfProtectionMatcher()` — 가입 요청자는 세션도 토큰도 없어서
   * **토큰을 실을 방법 자체가 없다.**
   *
   * ⚠️ 편의 스위치가 아니다. 인증이 필요한 경로에 이걸 붙이면 서버가 403 으로 막는다.
   */
  skipCsrf?: boolean
  /**
   * 401 을 만나도 **로그인으로 이동하지 않는다** — 세션 상태를 *물어보기만* 하는 호출 전용.
   *
   * ## 왜 필요한가
   * 개요 화면은 **로그인하지 않은 사람이 읽어야 하는 화면**이다. 그런데 세션 상태를 표시하려고
   * 프로브를 부르면 401 → 자동 이동이 걸려 **화면을 볼 기회 자체가 사라진다.** 시스템 설명을
   * 읽으려면 먼저 로그인해야 하는 모순이 생긴다.
   *
   * 이 스위치를 켜면 401 이 그냥 `ApiError` 로 올라오고, 화면이 "미인증" 이라는 **정보로**
   * 다룬다. 이동은 사용자가 버튼을 눌러 결정한다.
   *
   * ⚠️ 일반 데이터 조회에는 켜지 않는다. 켜면 세션이 끊겼을 때 화면이 조용히 비어 있고
   * 사용자는 왜 아무것도 없는지 알 수 없다.
   */
  suppressLoginRedirect?: boolean
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET',
    body,
    tenant,
    rawHeaders,
    skipCsrf = false,
    suppressLoginRedirect = false,
  } = options

  const headers: Record<string, string> = { Accept: 'application/json', ...rawHeaders }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (tenant) headers['X-Requested-Tenant'] = tenant

  let csrfHeaderName: string | undefined
  if (method !== 'GET' && !skipCsrf) {
    // ⚠️ 비동기다. cross-origin 배포에서는 쿠키를 못 읽어 게이트웨이에서 받아 오기 때문이다
    // (`api/csrf.ts`). 헤더 이름도 서버가 알려준 것을 그대로 쓴다.
    const csrf = await ensureCsrfToken()
    if (csrf) {
      headers[csrf.headerName] = csrf.token
      csrfHeaderName = csrf.headerName
    }
  }

  const url = toAbsolute(path)
  // ⚠️ 벽시계(`Date.now`)가 아니라 단조 시계를 쓴다. 시스템 시각이 조정되면 음수 지연이 찍힌다.
  const startedAtMs = performance.now()
  // 인스펙터 기록에 매번 같은 모양으로 채워지는 부분(`api/client.ts` 가 유일한 문이라 가능하다).
  const base = {
    startedAt: new Date().toLocaleTimeString(),
    method,
    path,
    url,
    route: classifyRoute(path),
    ...(tenant ? { requestedTenant: tenant } : {}),
    ...(rawHeaders && Object.keys(rawHeaders).length > 0 ? { rawHeaders } : {}),
    ...(csrfHeaderName ? { csrfHeaderName } : {}),
  }

  let res: Response
  try {
    res = await fetch(url, {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (e) {
    // 응답이 **아예 없는** 실패다(네트워크·CORS preflight 거절 등). 상태코드가 없으므로
    // 인스펙터에서도 status 칸이 비어야 한다 — 0 을 넣으면 서버가 0 을 준 것처럼 보인다.
    recordRequest({
      ...base,
      durationMs: Math.round(performance.now() - startedAtMs),
      ok: false,
      transportError: e instanceof Error ? e.message : String(e),
    })
    throw e
  }

  const durationMs = Math.round(performance.now() - startedAtMs)

  if (res.ok) {
    recordRequest({ ...base, durationMs, ok: true, status: res.status })
    if (res.status === 204) return undefined as T
    const text = await res.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  // 403 은 인가 실패일 수도, **토큰이 상해서**일 수도 있다(로그아웃·재로그인 뒤 세션이 바뀐 경우).
  // 구분할 방법이 응답에 없으므로 일단 캐시를 버린다. 인가 실패였다면 버려도 손해가 없고,
  // 토큰 문제였다면 버리지 않을 때 **새로고침 전까지 계속 403** 이다.
  if (res.status === 403) invalidateCsrfToken()

  const problem = await parseProblem(res)

  recordRequest({
    ...base,
    durationMs,
    ok: false,
    status: res.status,
    ...(problem.reasonCode ? { reasonCode: String(problem.reasonCode) } : {}),
    ...(problem.traceId ? { traceId: String(problem.traceId) } : {}),
    ...(problem.retryAfterSeconds !== undefined
      ? { retryAfterSeconds: problem.retryAfterSeconds }
      : {}),
  })

  // ⚠️ **top-level 이동이어야 한다.** fetch 가 302 를 따라가면 Keycloak 응답이 CORS 에 막혀
  // 콘솔에는 "CORS 에러" 만 뜨고 진짜 원인(미인증)이 가려진다(CLAUDE.md §6.1).
  // 이동 주소는 하드코딩하지 않고 **응답이 알려준 loginUrl** 을 쓴다.
  if (res.status === 401 && !suppressLoginRedirect && typeof problem.loginUrl === 'string') {
    // ⚠️ `loginUrl` 은 상대경로다. cross-origin 배치에서 그대로 쓰면 **FE 호스트로 이동**해
    // 404 가 난다(그 경로는 게이트웨이에만 있다).
    window.location.href = toAbsolute(problem.loginUrl)
  }

  throw new ApiError(res.status, problem)
}
