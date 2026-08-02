/**
 * queryKey 규약 — **테넌트를 키에 반드시 포함한다.**
 *
 * 테넌트는 URL 이 아니라 **헤더**로 전달되므로 `/api/orders` 는 어느 테넌트에서든 같은 경로다.
 * 키를 공유하면 acme 로 받은 목록이 globex 화면에 그대로 뜨고, 심지어 **네트워크 요청조차 나가지
 * 않는다.** 서버는 완벽히 격리돼 있는데 클라이언트 캐시가 그걸 무너뜨리는 것이라
 * 서버 로그를 아무리 봐도 안 보인다.
 */
export const queryKeys = {
  whoami: ['whoami'] as const,
  /**
   * 개요 화면의 세션 프로브. `whoami` 와 **키를 나눈다** — 같은 엔드포인트지만 401 을 다루는
   * 방식이 달라(이동 vs 표시) 캐시를 공유하면 한쪽의 실패가 다른 쪽의 동작을 바꾼다.
   */
  sessionProbe: ['session-probe'] as const,
  profile: ['profile'] as const,
  orders: (tenantId: string) => ['tenants', tenantId, 'orders'] as const,
  order: (tenantId: string, id: string) => ['tenants', tenantId, 'orders', id] as const,
  echo: (tenantId: string | null) => ['echo', tenantId] as const,
  myMemberships: ['memberships', 'mine'] as const,
  adminMembers: (tenantId: string) => ['admin', 'tenants', tenantId, 'members'] as const,
}
