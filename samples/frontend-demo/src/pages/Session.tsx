import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/problem'
import { ProblemAlert } from '../components/ProblemAlert'
import { ProofBanner } from '../components/ProofBanner'
import { useWhoami } from '../queries/hooks'

/**
 * 세션 · 토큰 — 예전의 첫 화면(`Landing`)이다.
 *
 * 첫 화면 자리는 개요(`Overview`)에 내주고 여기는 **"내 토큰에 무엇이 실려 있나"** 하나에
 * 집중한다. 그래야 개요를 미인증 상태에서도 읽을 수 있다 — 이 화면은 401 이면 로그인으로
 * 튕기는 일반 조회를 쓰기 때문이다.
 */
export function Session() {
  const { data, error, isLoading, refetch } = useWhoami()
  const [tenantInput, setTenantInput] = useState('')
  const navigate = useNavigate()

  // `/iam/debug/whoami` 는 IAM 의 **local 전용 프로브**다(`CallerProbeController` 가
  // `@Profile("local")`). alpha 에는 라우트 자체가 없어 404 다 — 고장이 아니라 그 환경에
  // 없는 것이므로 붉은 오류로 띄우면 "뭔가 깨졌다" 는 잘못된 신호를 준다.
  const probeMissing = error instanceof ApiError && error.status === 404

  return (
    <section>
      <h2>세션 · 토큰</h2>

      <ProofBanner
        claim={
          <>
            토큰은 브라우저에 없고, 다운스트림이 신뢰하는 <code>X-Tenant-Id</code> 는{' '}
            <strong>게이트웨이만 넣는다.</strong>
          </>
        }
        how={[
          '아래 표에서 토큰의 소속 테넌트(claim)를 확인한다',
          '"IAM 이 실제로 받은 테넌트 헤더" 두 줄을 본다',
          '소속 테넌트 버튼으로 이동해 정상 동작을 확인한다',
          '비소속 테넌트를 직접 입력해 이동해 본다',
        ]}
        expect={
          <>
            검증값(<code>X-Tenant-Id</code>)이 <strong>항상 비어 있어야 한다</strong> — IAM 라우트는
            테넌트 게이트를 거치지 않고, 게이트웨이가 인입 헤더를 지우기 때문이다. 비소속 테넌트로
            이동하면 <strong>403</strong> 이 나야 한다.
          </>
        }
        refs={[
          'gateway/.../gatewayIn/TenantGateFilter.kt',
          'gateway/.../config/GatewayRouteConfig.kt',
        ]}
      />

      {isLoading && <p>세션 확인 중…</p>}

      {probeMissing ? (
        <p className="note">
          이 환경에는 <code>/iam/debug/whoami</code> 프로브가 없다. IAM 의{' '}
          <code>CallerProbeController</code> 가 <code>@Profile(&quot;local&quot;)</code> 이라
          <strong> local 프로파일에만 존재</strong>한다. 세션 자체는 정상이며, 프로필·멤버십
          화면은 그대로 동작한다.
        </p>
      ) : (
        <ProblemAlert error={error} />
      )}
      {probeMissing && (
        <>
          <h3>테넌트 선택</h3>
          <p className="note">
            프로브가 없어 소속 목록을 보여줄 수 없다. 테넌트를 직접 입력해 이동한다 —
            비소속 테넌트를 넣으면 <strong>403</strong> 이 나야 정상이다.
          </p>
          <div className="row">
            <input
              placeholder="tenantId 입력"
              value={tenantInput}
              onChange={(e) => setTenantInput(e.target.value)}
            />
            <button disabled={!tenantInput} onClick={() => navigate(`/t/${tenantInput}/orders`)}>
              이동
            </button>
          </div>
        </>
      )}
      {data && (
        <>
          <h3>토큰이 말하는 나</h3>
          <dl className="kv">
            <dt>사용자</dt>
            <dd>{data.preferredUsername ?? '(없음)'}</dd>
            <dt>subject</dt>
            <dd>
              <code>{data.subject}</code>
              <small> ← 불변 식별자. 초대·멤버십의 대상은 이메일이 아니라 이 값이다</small>
            </dd>
            <dt>groups</dt>
            <dd>{data.groups?.join(', ') || '(없음)'}</dd>
            <dt>소속 테넌트</dt>
            <dd>
              {data.tenants?.length ? data.tenants.join(', ') : '(없음)'}
              <small> ← groups 에서 /tenants/* 만 뽑은 값. 인가의 유일한 근거다</small>
            </dd>
            <dt>audience</dt>
            <dd>
              {data.audience?.join(', ') || '(없음)'}
              <small> ← 이 토큰을 검증해도 되는 수신자 목록</small>
            </dd>
            <dt>토큰 만료</dt>
            <dd>
              {data.expiresAt}
              <small> ← 만료돼도 로그아웃되지 않는다. 게이트웨이가 refresh 로 갱신한다</small>
            </dd>
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
