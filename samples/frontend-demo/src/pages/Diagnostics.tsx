import { useState } from 'react'
import { orders } from '../api/orders'
import { ApiError } from '../api/problem'
import type { Echo } from '../api/types'
import { ProblemAlert } from '../components/ProblemAlert'

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
