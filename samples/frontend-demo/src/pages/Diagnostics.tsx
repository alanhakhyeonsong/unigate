import { useState } from 'react'
import { orders } from '../api/orders'
import { ApiError } from '../api/problem'
import type { Echo } from '../api/types'
import { ProblemAlert } from '../components/ProblemAlert'
import { ProofBanner } from '../components/ProofBanner'

/**
 * 위조 헤더 실험실 — 이 앱의 존재 이유에 가장 가까운 화면.
 *
 * 다운스트림이 **실제로 받은 헤더**를 되비추므로, 게이트웨이가 무엇을 지우고 무엇을 넣었는지가
 * 그대로 보인다.
 */
export function Diagnostics() {
  const [tenant, setTenant] = useState('')
  const [forgedTenantId, setForgedTenantId] = useState('')
  const [forgedAuth, setForgedAuth] = useState(false)
  const [result, setResult] = useState<Echo | null>(null)
  const [error, setError] = useState<unknown>(null)

  const send = async () => {
    setError(null)
    setResult(null)
    const raw: Record<string, string> = {}
    if (forgedTenantId) raw['X-Tenant-Id'] = forgedTenantId
    if (forgedAuth) raw['Authorization'] = 'Bearer forged-token-should-be-stripped'
    try {
      setResult(await orders.echo(tenant || null, raw))
    } catch (e) {
      setError(e instanceof ApiError ? e : e)
    }
  }

  const seen = (name: string) =>
    result
      ? (Object.entries(result.headers).find(([k]) => k.toLowerCase() === name)?.[1] ?? '(없음)')
      : '-'

  return (
    <section>
      <h2>진단 — 위조 헤더 실험실</h2>

      <ProofBanner
        claim={
          <>
            클라이언트가 보낸 신뢰 헤더는 <strong>덮어써지는 게 아니라 제거된다.</strong>{' '}
            다운스트림에 도달한 값은 반드시 게이트가 검증한 것이다.
          </>
        }
        how={[
          'X-Requested-Tenant 에 소속 테넌트를 넣는다',
          'X-Tenant-Id 에 남의 테넌트를 위조해 넣는다',
          '위조 Authorization 헤더도 함께 체크한다',
          '/api/echo 를 호출해 다운스트림이 실제로 받은 값을 본다',
        ]}
        expect={
          <>
            <code>X-Tenant-Id</code> 는 <strong>위조값이 아니라</strong> 게이트가 검증한 값이어야
            하고, <code>Authorization</code> 은 위조 문자열이 아니라 <strong>세션에서 재주입된
            JWT</strong> 여야 한다. 비소속 테넌트를 주장하면 다운스트림에 닿기 전에 403 이다.
          </>
        }
        refs={[
          'gateway/.../gatewayIn/TenantGateFilter.kt',
          'samples/downstream-demo/.../EchoController.kt',
        ]}
      />

      <p className="note">
        이 화면은 <strong>인스펙터가 볼 수 없는 것</strong>을 본다. 브라우저는 게이트웨이가 붙인
        헤더를 관측할 수 없어서, 다운스트림이 되비춰 주는 이 경로가 유일한 확인 수단이다.
      </p>

      <div className="row">
        <label>
          X-Requested-Tenant(주장)
          <input value={tenant} onChange={(e) => setTenant(e.target.value)} placeholder="acme" />
        </label>
        <label>
          X-Tenant-Id(위조 시도)
          <input
            value={forgedTenantId}
            onChange={(e) => setForgedTenantId(e.target.value)}
            placeholder="globex"
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={forgedAuth}
            onChange={(e) => setForgedAuth(e.target.checked)}
          />
          위조 Authorization 헤더 포함
        </label>
        <button onClick={send}>/api/echo 호출</button>
      </div>

      <ProblemAlert error={error} />

      {result && (
        <>
          <h3>다운스트림이 실제로 받은 것</h3>
          <dl className="kv">
            <dt>X-Tenant-Id</dt>
            <dd>
              <strong>{seen('x-tenant-id')}</strong>
              <small> ← 위조값이 아니라 게이트가 검증한 값이어야 한다</small>
            </dd>
            <dt>X-Requested-Tenant</dt>
            <dd>{seen('x-requested-tenant')}</dd>
            <dt>Authorization</dt>
            <dd>
              {result.authorization.jwt ? 'Bearer(JWT) — 게이트가 재주입' : '(JWT 아님)'}
              <small> ← 위조 헤더는 제거되고 세션의 토큰이 실린다</small>
            </dd>
            <dt>principal</dt>
            <dd>{result.principal ?? '(없음)'}</dd>
          </dl>
          <details>
            <summary>전체 헤더</summary>
            <pre>{JSON.stringify(result.headers, null, 2)}</pre>
          </details>
        </>
      )}
    </section>
  )
}
