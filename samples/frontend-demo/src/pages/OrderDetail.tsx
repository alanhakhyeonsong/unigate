import { useParams } from 'react-router-dom'
import { ProblemAlert } from '../components/ProblemAlert'
import { useOrder } from '../queries/hooks'
import { useCurrentTenant } from '../tenant/useCurrentTenant'

export function OrderDetail() {
  const tenantId = useCurrentTenant()!
  const { id } = useParams<{ id: string }>()
  const { data, error, isLoading } = useOrder(tenantId, id!)

  return (
    <section>
      <h2>주문 {id}</h2>
      <p className="note">
        남의 테넌트 자원과 없는 자원이 <strong>같은 404</strong> 다. 403 을 주면 "그 id 는
        존재한다" 를 알려주는 셈이라 일부러 구분하지 않는다.
      </p>
      <ProblemAlert error={error} />
      {isLoading && <p>불러오는 중…</p>}
      {data && <pre>{JSON.stringify(data, null, 2)}</pre>}
    </section>
  )
}
