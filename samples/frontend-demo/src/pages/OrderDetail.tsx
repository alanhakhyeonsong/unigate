import { useParams } from 'react-router-dom'
import { ProblemAlert } from '../components/ProblemAlert'
import { ProofBanner } from '../components/ProofBanner'
import { useOrder } from '../queries/hooks'
import { useCurrentTenant } from '../tenant/useCurrentTenant'

export function OrderDetail() {
  const tenantId = useCurrentTenant()!
  const { id } = useParams<{ id: string }>()
  const { data, error, isLoading } = useOrder(tenantId, id!)

  return (
    <section>
      <h2>주문 {id}</h2>

      <ProofBanner
        claim={
          <>
            <strong>없는 자원과 남의 자원은 구분되지 않는다.</strong> 둘 다 404 다.
          </>
        }
        how={[
          '내 테넌트의 주문 id 로 들어와 정상 응답을 본다',
          'URL 의 테넌트만 다른 소속 테넌트로 바꾼다',
          '아예 존재하지 않는 id 로도 들어가 본다',
        ]}
        expect="두 경우의 응답이 완전히 같아야 한다. 403 을 주면 '그 id 는 존재한다' 를 알려주는 셈이다."
        refs={['IAM_PLATFORM_DECISION.md §8.4 규약 6']}
      />

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
