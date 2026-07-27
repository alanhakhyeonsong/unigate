import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ProblemAlert } from '../components/ProblemAlert'
import { useCreateOrder, useOrders } from '../queries/hooks'
import { useCurrentTenant } from '../tenant/useCurrentTenant'

export function Orders() {
  const tenantId = useCurrentTenant()!
  const { data, error, isLoading } = useOrders(tenantId)
  const create = useCreateOrder(tenantId)
  const [item, setItem] = useState('')

  return (
    <section>
      <h2>주문 — 테넌트 {tenantId}</h2>
      <p className="note">
        같은 URL(<code>/api/orders</code>)에 테넌트는 <code>X-Requested-Tenant</code> 헤더로만
        전달된다. 그래서 캐시 키에 테넌트를 넣지 않으면 다른 테넌트의 목록이 그대로 보인다.
      </p>
      <ProblemAlert error={error} />
      {isLoading && <p>불러오는 중…</p>}
      {data && (
        <ul>
          {data.map((o) => (
            <li key={o.id}>
              <Link to={`/t/${tenantId}/orders/${o.id}`}>{o.id}</Link> — {o.item}{' '}
              <small>({o.tenantId})</small>
            </li>
          ))}
          {data.length === 0 && <li>(없음)</li>}
        </ul>
      )}

      <h3>주문 생성</h3>
      <p className="note">
        요청 본문에 <code>tenantId</code> 가 <strong>없다.</strong> 소유 테넌트는 서버가 검증된
        컨텍스트에서만 정한다 — 본문으로 받으면 남의 테넌트에 자원을 만들 수 있다.
      </p>
      <div className="row">
        <input value={item} onChange={(e) => setItem(e.target.value)} placeholder="품목" />
        <button disabled={!item || create.isPending} onClick={() => create.mutate(item)}>
          생성
        </button>
      </div>
      <ProblemAlert error={create.error} />
    </section>
  )
}
