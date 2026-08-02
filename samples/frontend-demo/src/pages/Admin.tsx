import { useState } from 'react'
import { ProblemAlert } from '../components/ProblemAlert'
import { ProofBanner } from '../components/ProofBanner'
import { useAdminMembers, useAdminMutations } from '../queries/hooks'

/**
 * 관리자 화면 — **역할로 숨기지 않는다.**
 *
 * FE 가 역할을 보고 메뉴를 감추면 "서버가 막았는가" 를 확인할 수 없다. 비관리자로 들어와
 * 403 이 그대로 보이는 것이 이 화면의 검증 가치다(P9c).
 *
 * 그리고 이 화면이 없으면 **두 번째 테넌트를 만들 수단이 없어** 테넌트 격리를 제대로 볼 수 없다.
 */
export function Admin() {
  const [tenantId, setTenantId] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [maxUsers, setMaxUsers] = useState('')
  const [target, setTarget] = useState('')
  const [inviteUserRef, setInviteUserRef] = useState('')
  const [inviteRole, setInviteRole] = useState('tenant-member')

  const members = useAdminMembers(target, target.length > 0)
  const m = useAdminMutations(target)

  return (
    <section>
      <h2>관리자</h2>

      <ProofBanner
        claim={
          <>
            접근을 막는 주체는 <strong>서버</strong>다. FE 가 메뉴를 숨겨서가 아니다.
          </>
        }
        how={[
          '관리자가 아닌 계정으로 이 화면을 연다 (메뉴는 그대로 보인다)',
          '테넌트 생성을 눌러 본다',
          '관리자 계정으로 다시 들어와 테넌트를 만든다',
          '만든 테넌트에 subject 로 멤버를 초대한다',
        ]}
        expect={
          <>
            비관리자에게는 <strong>403</strong> 이 응답으로 와야 한다(버튼이 사라지는 게 아니라).
            생성 직후 테넌트 상태는 <code>PENDING</code> 이고, group 프로비저닝이 끝나야{' '}
            <code>ACTIVE</code> 가 된다.
          </>
        }
        refs={['iam/.../iamIn/TenantAdminController.kt', 'iam/.../config/IamSecurityConfig.kt']}
      />

      <p className="note">
        <code>unigate-admin</code> realm 역할이 없으면 아래 모든 호출이 <strong>403</strong> 이다.
        메뉴를 숨기지 않는 이유는 그 사실을 눈으로 보기 위해서다.
      </p>

      <h3>테넌트 생성</h3>
      <p className="note">
        생성 직후 상태는 <code>PENDING</code> 이다. Keycloak group 프로비저닝이 outbox 를 거치고,
        완료되면 워커가 <code>ACTIVE</code> 로 전이시킨다.
      </p>
      <div className="row">
        <input placeholder="tenantId (slug)" value={tenantId} onChange={(e) => setTenantId(e.target.value)} />
        <input placeholder="표시 이름" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        <input placeholder="maxUsers (선택)" value={maxUsers} onChange={(e) => setMaxUsers(e.target.value)} />
        <button
          disabled={!tenantId || !displayName || m.createTenant.isPending}
          onClick={() =>
            m.createTenant.mutate({
              tenantId,
              displayName,
              ...(maxUsers ? { maxUsers: Number(maxUsers) } : {}),
            })
          }
        >
          생성
        </button>
      </div>
      <ProblemAlert error={m.createTenant.error} />
      {m.createTenant.data && <pre>{JSON.stringify(m.createTenant.data, null, 2)}</pre>}

      <h3>멤버 관리</h3>
      <div className="row">
        <input placeholder="대상 tenantId" value={target} onChange={(e) => setTarget(e.target.value)} />
      </div>
      <ProblemAlert error={members.error} />
      {members.data && (
        <table>
          <thead>
            <tr>
              <th>userRef</th>
              <th>역할</th>
              <th>상태</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {members.data.members.map((member) => (
              <tr key={member.userRef}>
                <td>
                  <code>{member.userRef}</code>
                </td>
                <td>{member.role}</td>
                <td>{member.status}</td>
                <td>
                  <button
                    onClick={() =>
                      m.changeRole.mutate({
                        userRef: member.userRef,
                        role: member.role === 'tenant-admin' ? 'tenant-member' : 'tenant-admin',
                      })
                    }
                  >
                    역할 전환
                  </button>
                  <button onClick={() => m.revoke.mutate(member.userRef)}>해제</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h4>초대</h4>
      <p className="note">
        대상은 이메일이 아니라 <strong>Keycloak sub</strong> 이다 — email 은 Keycloak SoT 라
        표류할 수 있어 식별자로 쓰지 않는다. 초대는 <strong>쿼터를 차지하지 않는다</strong>(수락 시 검사).
      </p>
      <div className="row">
        <input placeholder="userRef (sub)" value={inviteUserRef} onChange={(e) => setInviteUserRef(e.target.value)} />
        <select value={inviteRole} onChange={(e) => setInviteRole(e.target.value)}>
          <option value="tenant-member">tenant-member</option>
          <option value="tenant-admin">tenant-admin</option>
        </select>
        <button
          disabled={!target || !inviteUserRef || m.invite.isPending}
          onClick={() => m.invite.mutate({ userRef: inviteUserRef, role: inviteRole })}
        >
          초대
        </button>
      </div>
      <ProblemAlert error={m.invite.error} />
      <ProblemAlert error={m.changeRole.error} />
      <ProblemAlert error={m.revoke.error} />
    </section>
  )
}
