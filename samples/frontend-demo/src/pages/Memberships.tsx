import { ProblemAlert } from '../components/ProblemAlert'
import { useAcceptInvite, useMyMemberships, useWhoami } from '../queries/hooks'

/**
 * 내 멤버십 — **토큰 claim 과 도메인 목록의 차이를 나란히 보여주는 화면.**
 *
 * 이 두 줄이 어긋나는 순간(수락 직후·해제 직후)이 이 플랫폼에서 가장 헷갈리는 지점이고,
 * 그걸 감추지 않고 드러내는 것이 이 화면의 목적이다.
 */
export function Memberships() {
  const { data, error, isLoading } = useMyMemberships()
  const whoami = useWhoami()
  const accept = useAcceptInvite()

  const claimTenants = whoami.data?.tenants ?? []

  return (
    <section>
      <h2>내 멤버십</h2>
      <ProblemAlert error={error} />

      <div className="note">
        <strong>토큰 claim</strong>: {claimTenants.join(', ') || '(없음)'}
        <br />
        <strong>도메인 목록</strong>: {(data ?? []).map((m) => `${m.tenantId}(${m.status})`).join(', ') || '(없음)'}
        <br />
        인가는 <strong>claim 으로만</strong> 판단한다. 아래 목록에 있어도 claim 에 없으면 API 는 403 이다.
      </div>

      {isLoading && <p>불러오는 중…</p>}
      <table>
        <thead>
          <tr>
            <th>테넌트</th>
            <th>역할</th>
            <th>상태</th>
            <th>claim 에 있는가</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {(data ?? []).map((m) => (
            <tr key={m.tenantId}>
              <td>
                {m.tenantDisplayName ?? m.tenantId} <small>({m.tenantId})</small>
              </td>
              <td>{m.role}</td>
              <td>{m.status}</td>
              <td>{claimTenants.includes(m.tenantId) ? '있음' : <strong>없음</strong>}</td>
              <td>
                {m.status === 'INVITED' && (
                  <button disabled={accept.isPending} onClick={() => accept.mutate(m.tenantId)}>
                    수락
                  </button>
                )}
              </td>
            </tr>
          ))}
          {data?.length === 0 && (
            <tr>
              <td colSpan={5}>(없음)</td>
            </tr>
          )}
        </tbody>
      </table>
      <ProblemAlert error={accept.error} />

      {accept.isSuccess && (
        <div className="alert">
          수락됐다. <strong>하지만 아직 그 테넌트로 API 를 부르면 403 이다.</strong>
          <br />
          <small>
            group 투영이 outbox 를 거치고, 이미 발급된 토큰은 만료(5분) 전까지 옛 소속을 담고 있다.
            <strong> 재로그인해야 claim 이 갱신된다.</strong> 캐시 무효화로 해결되는 문제가 아니다.
          </small>
        </div>
      )}
    </section>
  )
}
