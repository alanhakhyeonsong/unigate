import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ProblemAlert } from '../components/ProblemAlert'
import { ProofBanner } from '../components/ProofBanner'
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

      <ProofBanner
        claim={
          <>
            테넌트 격리는 <strong>서버와 클라이언트 캐시 양쪽</strong>에서 깨질 수 있고, 둘의 증상이
            전혀 다르다.
          </>
        }
        how={[
          '탭을 두 개 열어 서로 다른 테넌트의 이 화면을 띄운다',
          '각각 주문을 만들어 목록이 섞이지 않는지 본다',
          'URL 의 테넌트를 비소속 값으로 바꿔 본다',
          '인스펙터에서 요청이 실제로 나갔는지 확인한다',
        ]}
        expect={
          <>
            비소속 테넌트는 <strong>403</strong> 이어야 한다. 그리고 테넌트를 바꿨는데{' '}
            <strong>요청이 아예 안 나가고</strong> 남의 목록이 보인다면 그건 서버가 아니라{' '}
            <strong>queryKey 에 테넌트가 빠진 것</strong>이다 — 서버 로그에는 아무 흔적이 없다.
          </>
        }
        refs={['samples/frontend-demo/src/api/queryKeys.ts', 'IAM_PLATFORM_DECISION.md §8.4']}
      />

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
