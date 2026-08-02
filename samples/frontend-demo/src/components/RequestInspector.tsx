import { useState, useSyncExternalStore } from 'react'
import {
  ROUTE_NOTES,
  clearRequests,
  getRequestsSnapshot,
  subscribeRequests,
  type RequestRecord,
} from '../inspect/requestLog'

/**
 * 요청 인스펙터 — **화면 어디에 있든 방금 무슨 요청이 나갔는지 보인다.**
 *
 * ## 왜 진단 화면에만 두지 않는가
 * "진단" 화면은 위조 헤더를 **일부러 만들어** 보는 실험실이다. 그런데 대부분의 헷갈리는 순간은
 * 일반 화면에서 생긴다 — 멤버십을 수락했는데 403, 프로필은 되는데 주문만 403, 로그인은 됐는데
 * 매 요청이 401. 그때 브라우저 devtools 를 열어 해석하는 대신 **같은 화면에서 바로 보이게** 한다.
 *
 * ## ⚠️ 이 패널이 아는 것과 모르는 것
 * FE 가 보낸 것과 서버가 돌려준 것만 안다. **게이트웨이가 붙인 `Authorization`·검증된
 * `X-Tenant-Id` 는 여기서 볼 수 없다** — 브라우저에서 원리적으로 관측 불가능하다.
 * 그걸 보려면 다운스트림이 되비추는 `/api/echo`(진단 화면)를 써야 한다.
 * 상세는 `inspect/requestLog.ts` KDoc.
 */
export function RequestInspector() {
  const records = useSyncExternalStore(subscribeRequests, getRequestsSnapshot)
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)

  const last = records[0]
  const detail = records.find((r) => r.id === selected) ?? null

  return (
    <div className="inspector">
      <button className="inspector-toggle" onClick={() => setOpen((v) => !v)}>
        {open ? '▼' : '▲'} 요청 인스펙터 ({records.length})
        {last && (
          <span className={last.ok ? 'ok' : 'bad'}>
            {' '}
            마지막: {last.method} {last.path} → {statusLabel(last)}
          </span>
        )}
      </button>

      {open && (
        <div className="inspector-body">
          <p className="note">
            FE 가 <strong>보낸 것</strong>과 서버가 <strong>돌려준 것</strong>만 보인다. 게이트웨이가
            재주입한 <code>Authorization</code> 과 검증된 <code>X-Tenant-Id</code> 는{' '}
            <strong>여기서 볼 수 없다</strong> — 브라우저에서 관측 불가능하다. 그건 다운스트림이
            되비추는 <code>/api/echo</code>(진단 화면)로 확인한다.
          </p>

          <div className="inspector-split">
            <table>
              <thead>
                <tr>
                  <th>시각</th>
                  <th>요청</th>
                  <th>라우트(추론)</th>
                  <th>결과</th>
                  <th>ms</th>
                </tr>
              </thead>
              <tbody>
                {records.map((r) => (
                  <tr
                    key={r.id}
                    onClick={() => setSelected(r.id)}
                    className={r.id === selected ? 'selected' : undefined}
                  >
                    <td>{r.startedAt}</td>
                    <td>
                      {r.method} <code>{r.path}</code>
                      {r.requestedTenant && <small> ⟨{r.requestedTenant}⟩</small>}
                    </td>
                    <td>
                      <small>{r.route}</small>
                    </td>
                    <td className={r.ok ? 'ok' : 'bad'}>{statusLabel(r)}</td>
                    <td>{r.durationMs}</td>
                  </tr>
                ))}
                {records.length === 0 && (
                  <tr>
                    <td colSpan={5}>(아직 요청이 없다)</td>
                  </tr>
                )}
              </tbody>
            </table>

            {detail && <RecordDetail record={detail} />}
          </div>

          <button
            onClick={() => {
              clearRequests()
              setSelected(null)
            }}
          >
            기록 지우기
          </button>
        </div>
      )}
    </div>
  )
}

/** 상태 표기. **네트워크 실패는 상태코드가 없다** — 0 으로 적으면 서버가 0 을 준 것처럼 보인다. */
function statusLabel(r: RequestRecord): string {
  if (r.transportError) return '전송 실패'
  return String(r.status ?? '-')
}

function RecordDetail({ record }: { record: RequestRecord }) {
  return (
    <div className="inspector-detail">
      <dl className="kv">
        <dt>절대 주소</dt>
        <dd>
          <code>{record.url}</code>
        </dd>
        <dt>게이트웨이 라우트</dt>
        <dd>
          <code>{record.route}</code>
          <small> — {ROUTE_NOTES[record.route]}</small>
        </dd>
        <dt>테넌트 주장</dt>
        <dd>
          {record.requestedTenant ? (
            <>
              <code>X-Requested-Tenant: {record.requestedTenant}</code>
              <small> ← 주장일 뿐이다. 검증은 게이트가 한다</small>
            </>
          ) : (
            '(없음)'
          )}
        </dd>
        <dt>CSRF 헤더</dt>
        <dd>
          {record.csrfHeaderName ? (
            <code>{record.csrfHeaderName}</code>
          ) : record.method === 'GET' ? (
            '(GET — 불필요)'
          ) : (
            <>
              <strong>(없음)</strong>
              <small> ← 쓰기인데 토큰이 없다. 403 이면 여기가 원인일 수 있다</small>
            </>
          )}
        </dd>
        {record.rawHeaders && (
          <>
            <dt>손으로 넣은 헤더</dt>
            <dd>
              <pre>{JSON.stringify(record.rawHeaders, null, 2)}</pre>
              <small>← 게이트웨이가 지웠는지는 /api/echo 로만 확인된다</small>
            </dd>
          </>
        )}
        <dt>reasonCode</dt>
        <dd>{record.reasonCode ?? '(없음)'}</dd>
        <dt>traceId</dt>
        <dd>
          {record.traceId ? (
            <>
              <code>{record.traceId}</code>
              <small> ← 서버 로그를 이 값으로 찾는다</small>
            </>
          ) : (
            '(없음)'
          )}
        </dd>
        {record.retryAfterSeconds !== undefined && (
          <>
            <dt>Retry-After</dt>
            <dd>
              {record.retryAfterSeconds}s
              <small> ← cross-origin 에서 이 값이 보인다는 것은 exposedHeaders 가 맞다는 뜻</small>
            </dd>
          </>
        )}
        {record.transportError && (
          <>
            <dt>전송 실패</dt>
            <dd>
              <strong>{record.transportError}</strong>
              <small> ← 응답이 없다. CORS·네트워크·서버 다운을 구분할 정보가 브라우저에 없다</small>
            </dd>
          </>
        )}
      </dl>
    </div>
  )
}
