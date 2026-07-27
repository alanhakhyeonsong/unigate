import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ProblemAlert } from '../components/ProblemAlert'
import { useWhoami } from '../queries/hooks'

export function Landing() {
  const { data, error, isLoading, refetch } = useWhoami()
  const [tenantInput, setTenantInput] = useState('')
  const navigate = useNavigate()

  if (isLoading) return <p>세션 확인 중…</p>

  return (
    <section>
      <h2>세션 상태</h2>
      <ProblemAlert error={error} />
      {data && (
        <>
          <dl className="kv">
            <dt>사용자</dt>
            <dd>{data.preferredUsername ?? '(없음)'}</dd>
            <dt>subject</dt>
            <dd>
              <code>{data.subject}</code>
            </dd>
            <dt>groups</dt>
            <dd>{data.groups?.join(', ') || '(없음)'}</dd>
            <dt>소속 테넌트</dt>
            <dd>{data.tenants?.length ? data.tenants.join(', ') : '(없음)'}</dd>
            <dt>토큰 만료</dt>
            <dd>{data.expiresAt}</dd>
          </dl>

          <h3>IAM 이 실제로 받은 테넌트 헤더</h3>
          <p className="note">
            IAM 라우트에는 테넌트 게이트를 걸지 않지만, 게이트웨이가 <code>X-Tenant-Id</code> 를
            제거한다. 아래 <strong>검증값이 항상 비어 있는 것</strong>이 그 증거다.
          </p>
          <dl className="kv">
            <dt>X-Tenant-Id (검증값)</dt>
            <dd>{data.receivedTenantHeader ?? '(없음 — 제거됨)'}</dd>
            <dt>X-Requested-Tenant (주장)</dt>
            <dd>{data.receivedRequestedTenantHeader ?? '(없음)'}</dd>
          </dl>

          <h3>테넌트 선택</h3>
          <p className="note">
            테넌트는 URL 이 SoT 다. 탭을 두 개 열어 서로 다른 테넌트를 동시에 볼 수 있다.
          </p>
          <div className="row">
            {(data.tenants ?? []).map((t) => (
              <button key={t} onClick={() => navigate(`/t/${t}/orders`)}>
                {t} 로 이동
              </button>
            ))}
            <input
              placeholder="비소속 테넌트도 입력해 보라 (403 확인)"
              value={tenantInput}
              onChange={(e) => setTenantInput(e.target.value)}
            />
            <button disabled={!tenantInput} onClick={() => navigate(`/t/${tenantInput}/orders`)}>
              이동
            </button>
          </div>
        </>
      )}
      <button onClick={() => refetch()}>다시 조회</button>
    </section>
  )
}
