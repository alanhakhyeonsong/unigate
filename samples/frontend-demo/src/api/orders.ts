import { request } from './client'
import type { Echo, Order } from './types'

/**
 * 다운스트림 호출. **테넌트를 인자로 강제**한다 — 빠뜨리면 컴파일이 안 된다.
 *
 * 저장소에서 `findById(tenant, id)` 로 테넌트 없는 질의를 못 만들게 한 것과 같은 발상이다
 * (`docs/learning/24`). 잊을 수 있는 자리를 타입으로 없앤다.
 */
export const orders = {
  list: (tenant: string) => request<Order[]>('/api/orders', { tenant }),
  get: (tenant: string, id: string) => request<Order>(`/api/orders/${id}`, { tenant }),
  // ⚠️ 본문에 tenantId 가 **없다.** 자원의 소유 테넌트는 서버가 검증된 컨텍스트에서만 정한다.
  create: (tenant: string, item: string) =>
    request<Order>('/api/orders', { method: 'POST', tenant, body: { item } }),
  echo: (tenant: string | null, rawHeaders?: Record<string, string>) =>
    request<Echo>('/api/echo', { tenant: tenant ?? undefined, rawHeaders }),
}
