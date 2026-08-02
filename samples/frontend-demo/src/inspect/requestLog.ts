/**
 * 요청 기록 저장소 — **인스펙터가 보는 유일한 원천.**
 *
 * ## 왜 React 상태가 아닌가
 * 기록하는 쪽(`api/client.ts`)은 React 바깥의 평범한 함수다. 여기서 훅을 쓸 수 없고,
 * Context 로 내리면 **모든 API 호출이 컴포넌트 트리를 알아야 하는** 뒤집힌 의존이 된다.
 * 그래서 모듈 수준 저장소 + `useSyncExternalStore` 로 구독만 붙인다.
 *
 * ## ⚠️ 이 로그가 말할 수 있는 것과 없는 것
 *
 * | 볼 수 있다 | 볼 수 없다 |
 * |---|---|
 * | FE 가 **보낸** 것 (메서드·경로·테넌트 주장·위조 헤더) | 게이트웨이가 **붙인** 헤더 (`Authorization` 재주입, 검증된 `X-Tenant-Id`) |
 * | 서버가 **돌려준** 것 (상태·reasonCode·traceId·Retry-After) | 다운스트림이 **실제로 받은** 것 |
 *
 * 오른쪽 열은 브라우저에서 원리적으로 관측 불가능하다. 그것을 보려면 다운스트림이 되비추는
 * `/api/echo` 를 써야 한다(진단 화면). **인스펙터가 그걸 아는 척하면 안 된다** — 그 순간
 * 이 콘솔은 관찰 도구가 아니라 추측 도구가 된다.
 */

export interface RequestRecord {
  id: number
  /** 요청 시작 시각(표시용). 서버 시각이 아니다. */
  startedAt: string
  method: string
  /** 애플리케이션 경로(`/api/orders`). 환경별 base 를 뺀 값이라 환경 간 비교가 된다. */
  path: string
  /** 실제로 때린 절대 주소. cross-origin 배포에서 base 가 붙었는지 확인용. */
  url: string
  /** 어느 라우트에 걸리는가 — 경로에서 추론한 값이다(서버가 알려준 게 아니다). */
  route: GatewayRoute
  /** 클라이언트의 테넌트 **주장**(`X-Requested-Tenant`). 검증은 게이트가 한다. */
  requestedTenant?: string
  /** 위조 실험용으로 손으로 넣은 헤더. 일반 화면에서는 비어 있다. */
  rawHeaders?: Record<string, string>
  /** CSRF 헤더를 실었는가. 쓰기 요청의 403 을 볼 때 첫 번째로 확인할 값이다. */
  csrfHeaderName?: string
  durationMs: number
  status?: number
  ok: boolean
  reasonCode?: string
  traceId?: string
  retryAfterSeconds?: number
  /** 네트워크·CORS 실패처럼 **응답 자체가 없는** 경우. status 는 비어 있다. */
  transportError?: string
}

/**
 * 경로가 어느 게이트웨이 라우트에 걸리는지 **추론**한다.
 *
 * `GatewayRouteConfig.kt` 의 선언을 그대로 옮긴 것이며 서버가 알려준 값이 아니다.
 * 라우트 정의가 바뀌면 여기가 조용히 틀린다 — 그래서 화면에 "추론" 이라고 적는다.
 */
export type GatewayRoute =
  | 'iam-public'
  | 'iam-authenticated'
  | 'downstream-demo'
  | 'gateway-self'
  | 'unknown'

const IAM_PUBLIC_PATHS = ['/iam/register']
/** 게이트웨이가 스스로 처리하고 프록시하지 않는 경로들(`SecurityConfig`·`CsrfTokenEndpoint`). */
const GATEWAY_SELF_PREFIXES = ['/csrf', '/logout', '/oauth2/', '/login/', '/actuator', '/fallback/']

export function classifyRoute(path: string): GatewayRoute {
  if (GATEWAY_SELF_PREFIXES.some((p) => path.startsWith(p))) return 'gateway-self'
  if (IAM_PUBLIC_PATHS.includes(path)) return 'iam-public'
  if (path.startsWith('/iam/')) return 'iam-authenticated'
  if (path.startsWith('/api/')) return 'downstream-demo'
  return 'unknown'
}

/** 라우트별로 게이트웨이가 무엇을 하는지 — 화면에서 한 줄로 설명하기 위한 사본. */
export const ROUTE_NOTES: Record<GatewayRoute, string> = {
  'iam-public':
    '공개 라우트. 인증 없음 · CSRF 예외 · 전용 rate limit · tokenRelay 없음(릴레이할 토큰이 없다)',
  'iam-authenticated':
    '인증 라우트. 인입 Authorization/Cookie/X-Tenant-Id strip → tokenRelay. 테넌트 게이트는 걸지 않는다',
  'downstream-demo':
    'rate limit → 테넌트 게이트(coarse) → stripPrefix → 인입 헤더 strip → tokenRelay → circuit breaker',
  'gateway-self': '게이트웨이가 직접 처리한다. 프록시되지 않는다',
  unknown: '알려진 라우트 패턴에 걸리지 않는다 — 404 가 정상이다',
}

const MAX_RECORDS = 50

let records: RequestRecord[] = []
let nextId = 1
const listeners = new Set<() => void>()

function emit(): void {
  for (const listener of listeners) listener()
}

export function recordRequest(record: Omit<RequestRecord, 'id'>): void {
  // 새 것이 앞에 온다. 화면이 역순 정렬을 또 하지 않아도 되게.
  records = [{ ...record, id: nextId++ }, ...records].slice(0, MAX_RECORDS)
  emit()
}

export function clearRequests(): void {
  records = []
  emit()
}

export function subscribeRequests(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * ⚠️ **같은 배열 참조를 돌려줘야 한다.**
 *
 * `useSyncExternalStore` 는 스냅샷을 `Object.is` 로 비교한다. 여기서 매번 새 배열을 만들면
 * 값이 안 바뀌어도 항상 다르다고 판정돼 **무한 렌더 루프**가 난다. 그래서 변경 시에만
 * `records` 를 새로 대입하고 이 함수는 그 참조를 그대로 반환한다.
 */
export function getRequestsSnapshot(): RequestRecord[] {
  return records
}
