import { useState } from 'react'
import { PendingBadge } from '../components/PendingBadge'
import { ProblemAlert } from '../components/ProblemAlert'
import { ProofBanner } from '../components/ProofBanner'
import { useChangeEmail, useProfile, useUpdateProfile } from '../queries/hooks'

export function Profile() {
  const { data, error, isLoading } = useProfile()
  const updateProfile = useUpdateProfile()
  const changeEmail = useChangeEmail()
  const [displayName, setDisplayName] = useState('')
  const [newEmail, setNewEmail] = useState('')
  // 성공과 보상을 응답으로 구분할 수 없어 요청 값을 기억해 둔다(hooks.ts KDoc 참조).
  const [requested, setRequested] = useState<string | null>(null)

  const settled = requested && !data?.pendingEmail
  const succeeded = settled && data?.email === requested

  return (
    <section>
      <h2>프로필</h2>

      <ProofBanner
        claim={
          <>
            IAM 이 소유한 값은 <strong>즉시</strong> 바뀌고, Keycloak 이 소유한 값은{' '}
            <strong>워커를 거쳐 나중에</strong> 바뀐다.
          </>
        }
        how={[
          '표시 이름을 바꾼다 — 화면이 곧바로 갱신된다',
          '이메일 변경을 요청한다 — 202 로 끝나고 "반영 대기" 배지가 붙는다',
          '배지가 사라질 때까지 둔다(폴링한다)',
        ]}
        expect={
          <>
            이메일은 <strong>확정 값과 대기 값이 따로</strong> 보여야 한다. 요청 즉시 확정 값이
            바뀌면 실패했을 때 되돌릴 근거가 사라진다 — 그래서 일부러 나눠 둔다.
          </>
        }
        refs={['iam/.../user/service/ChangeMyEmailService.kt', 'IAM_PLATFORM_DECISION.md §6.3 UC-3']}
      />

      {isLoading && <p>불러오는 중…</p>}
      <ProblemAlert error={error} />
      {data && (
        <>
          <dl className="kv">
            <dt>이메일</dt>
            <dd>
              {data.email} {data.pendingEmail && <PendingBadge value={data.pendingEmail} />}
            </dd>
            <dt>표시 이름</dt>
            <dd>{data.displayName}</dd>
            <dt>온보딩 상태</dt>
            <dd>{data.onboardingState}</dd>
          </dl>

          {settled && (
            <div className="alert">
              {succeeded
                ? `이메일 변경이 반영됐다: ${data.email}`
                : `이메일 변경이 취소됐다(보상). 요청했던 값: ${requested}`}
              <br />
              <small>
                ⚠️ 서버는 성공과 실패를 같은 모양(`pendingEmail: null`)으로 알려준다. 이 문구는 FE 가
                요청 값을 기억해 추측한 것이다 — 새로고침하면 구분할 수 없다.
              </small>
            </div>
          )}

          <h3>표시 이름 변경 (즉시 반영)</h3>
          <div className="row">
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            <button
              disabled={!displayName || updateProfile.isPending}
              onClick={() => updateProfile.mutate({ displayName })}
            >
              변경
            </button>
          </div>
          <ProblemAlert error={updateProfile.error} />

          <h3>이메일 변경 (202 · 워커가 반영)</h3>
          <p className="note">
            이 요청이 200 이 아니라 <strong>202</strong> 인 것이 요점이다. 반환 시점에 Keycloak 은
            아직 옛 주소를 안다.
          </p>
          <div className="row">
            <input value={newEmail} onChange={(e) => setNewEmail(e.target.value)} />
            <button
              disabled={!newEmail || changeEmail.isPending}
              onClick={() => {
                setRequested(newEmail)
                changeEmail.mutate(newEmail)
              }}
            >
              변경 요청
            </button>
          </div>
          <ProblemAlert error={changeEmail.error} />
        </>
      )}
    </section>
  )
}
