import { CSRF_HEADER, readCsrfToken } from './csrf'
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
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, tenant, rawHeaders } = options

  const headers: Record<string, string> = { Accept: 'application/json', ...rawHeaders }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (tenant) headers['X-Requested-Tenant'] = tenant

  if (method !== 'GET') {
    const token = readCsrfToken()
    if (token) headers[CSRF_HEADER] = token
  }

  const res = await fetch(toAbsolute(path), {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (res.ok) {
    if (res.status === 204) return undefined as T
    const text = await res.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  const problem = await parseProblem(res)

  // ⚠️ **top-level 이동이어야 한다.** fetch 가 302 를 따라가면 Keycloak 응답이 CORS 에 막혀
  // 콘솔에는 "CORS 에러" 만 뜨고 진짜 원인(미인증)이 가려진다(CLAUDE.md §6.1).
  // 이동 주소는 하드코딩하지 않고 **응답이 알려준 loginUrl** 을 쓴다.
  if (res.status === 401 && typeof problem.loginUrl === 'string') {
    // ⚠️ `loginUrl` 은 상대경로다. cross-origin 배치에서 그대로 쓰면 **FE 호스트로 이동**해
    // 404 가 난다(그 경로는 게이트웨이에만 있다).
    window.location.href = toAbsolute(problem.loginUrl)
  }

  throw new ApiError(res.status, problem)
}
