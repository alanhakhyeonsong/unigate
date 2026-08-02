import { Link } from 'react-router-dom'
import { toAbsolute } from '../api/env'
import { useSessionProbe } from '../queries/hooks'

/**
 * 개요 — **이 콘솔의 첫 화면이자, 로그인 없이 읽을 수 있는 유일한 설명 화면.**
 *
 * ## 왜 만들었나
 * 예전 첫 화면은 "세션 상태" 였다. 그래서 처음 여는 사람은 ① 무슨 시스템인지 모른 채
 * ② 곧바로 Keycloak 로그인으로 튕겼다. 콘솔이 무엇을 보여주려는 물건인지 설명할 자리가
 * 아예 없었다.
 *
 * ## 이 화면이 지키는 제약
 * **다른 조회를 부르지 않는다.** 세션 프로브 하나만, 그것도 401 에 이동하지 않는 형태로 부른다
 * (`iam.whoamiQuiet`). 하나라도 일반 조회를 넣으면 미인증 방문자가 이 화면을 볼 수 없게 된다.
 */
export function Overview() {
  const probe = useSessionProbe()

  return (
    <section>
      <h2>unigate 검증 콘솔 — 개요</h2>

      <p>
        <strong>unigate 는 MSA 공통 IAM 플랫폼이다.</strong> 이 콘솔은 그 제품 화면이 아니라,
        게이트웨이·IAM·다운스트림 사이의 <strong>인증/인가 경계가 실제로 지켜지는지 눈으로 보는
        검증 장치</strong>다. 그래서 예쁘게 감추는 대신 <strong>실패를 그대로 드러낸다</strong> —
        권한이 없으면 메뉴를 숨기지 않고 403 을 보여준다.
      </p>

      <SessionCard probe={probe} />

      <h3>요청이 지나는 길</h3>
      <p className="note">
        브라우저는 <strong>세션 쿠키만</strong> 가진다. 토큰은 게이트웨이 세션 안에만 있고 FE 는
        영원히 보지 못한다 — 그것이 BFF 를 쓰는 이유다.
      </p>
      <pre>{FLOW_DIAGRAM}</pre>

      <h3>누가 무엇을 판단하는가</h3>
      <p className="note">
        <strong>게이트는 최종 방어선이 아니다.</strong> "빨리 거절" 하는 자리이고, 다운스트림은
        자기 인가를 별도로 가져야 한다.
      </p>
      <table>
        <thead>
          <tr>
            <th>계층</th>
            <th>판단 범위</th>
            <th>판단 근거</th>
            <th>하지 않는 것</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>
              <strong>게이트웨이</strong>
            </td>
            <td>인증 · coarse 인가(테넌트 소속)</td>
            <td>토큰 claim + 정적 라우트 설정</td>
            <td>
              <strong>도메인 조회를 절대 하지 않는다</strong>
            </td>
          </tr>
          <tr>
            <td>
              <strong>IAM</strong>
            </td>
            <td>회원 · 프로필 · 테넌트 · 멤버십</td>
            <td>자기 DB + Keycloak Admin(봉인)</td>
            <td>다운스트림의 자원을 모른다</td>
          </tr>
          <tr>
            <td>
              <strong>다운스트림</strong>
            </td>
            <td>fine 인가(자원 소유권 · 상태)</td>
            <td>토큰 + 검증된 헤더 + 자기 도메인 데이터</td>
            <td>Keycloak 에 직접 접근하지 않는다(JWKS 검증만)</td>
          </tr>
        </tbody>
      </table>

      <h3>순서대로 밟아 보기</h3>
      <p className="note">
        각 단계는 <strong>다른 경계</strong>를 건드린다. 순서를 건너뛰면 뒤 단계가 왜 실패하는지
        알 수 없다.
      </p>
      <ol className="steps">
        {STEPS.map((step) => (
          <li key={step.title}>
            <div>
              <strong>{step.title}</strong> — {step.what}
            </div>
            <small>{step.why}</small>
            <div>
              <Link to={step.to}>{step.linkLabel}</Link>
            </div>
          </li>
        ))}
      </ol>

      <h3>화면 지도 — 무엇을 어디서 확인하나</h3>
      <table>
        <thead>
          <tr>
            <th>화면</th>
            <th>증명하는 것</th>
          </tr>
        </thead>
        <tbody>
          {SCREEN_MAP.map((row) => (
            <tr key={row.to}>
              <td>
                <Link to={row.to}>{row.name}</Link>
              </td>
              <td>{row.proves}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h3>먼저 알아 두면 덜 헷갈리는 것</h3>
      <ul className="gotchas">
        <li>
          <strong>초대를 수락해도 바로 되지 않는다.</strong> 멤버십은 IAM DB → outbox → Keycloak
          group 순으로 흐르고, <strong>이미 발급된 토큰은 만료 전까지 옛 소속을 담는다.</strong>{' '}
          재로그인해야 claim 이 갱신된다 — 새로고침이나 캐시 무효화로 해결되지 않는다.
        </li>
        <li>
          <strong>가입만으로는 로그인할 수 없다.</strong> 가입 요청에 비밀번호가 없고 IAM 이 만드는
          Keycloak 사용자는 credential 이 없다. 자격증명은 Keycloak 소관이다.
        </li>
        <li>
          <strong>없는 자원과 남의 자원은 같은 404 다.</strong> 403 을 주면 "그 id 는 존재한다" 를
          알려주는 셈이라 일부러 구분하지 않는다.
        </li>
        <li>
          <strong>관리자가 아니어도 관리 메뉴가 보인다.</strong> 역할로 숨기면 서버가 막았는지
          확인할 수 없다. 403 이 그대로 보이는 것이 이 콘솔의 검증 가치다.
        </li>
      </ul>
    </section>
  )
}

function SessionCard({ probe }: { probe: ReturnType<typeof useSessionProbe> }) {
  const { state, data, loginUrl } = probe

  if (state === 'loading') return <p className="note">세션 확인 중…</p>

  if (state === 'anonymous') {
    return (
      <div className="note">
        <strong>현재: 로그인하지 않은 상태다.</strong>
        <div>
          아래 설명은 그대로 읽을 수 있다. 실제 호출을 해 보려면 로그인이 필요하다 —{' '}
          <strong>주소창 이동(top-level navigation)</strong> 이어야 한다. <code>fetch</code> 로
          보내면 302 를 따라간 Keycloak 응답이 CORS 에 막혀 콘솔에는 "CORS 에러" 만 뜨고 진짜
          원인(미인증)이 가려진다.
        </div>
        {loginUrl ? (
          <button onClick={() => (window.location.href = toAbsolute(loginUrl))}>로그인</button>
        ) : (
          <small>
            (서버가 <code>loginUrl</code> 을 주지 않았다. 게이트웨이의 401 본문을 확인해야 한다)
          </small>
        )}
      </div>
    )
  }

  if (state === 'authenticated-no-probe') {
    return (
      <div className="note">
        <strong>현재: 로그인된 상태다.</strong> 다만 이 환경에는{' '}
        <code>/iam/debug/whoami</code> 프로브가 없어 토큰 상세를 볼 수 없다 —{' '}
        <code>CallerProbeController</code> 가 <code>@Profile(&quot;local&quot;)</code> 이기 때문이다.
        <br />
        <small>
          404 가 곧 인증의 증거다: 미인증이었다면 게이트웨이가 <strong>라우팅 전에</strong> 401 을
          냈을 것이다.
        </small>
      </div>
    )
  }

  if (state === 'unknown') {
    return (
      <div className="alert">
        세션 상태를 판단할 수 없다(5xx 또는 전송 실패). 아래 인스펙터에서 마지막 요청을 확인한다.
      </div>
    )
  }

  return (
    <div className="note">
      <strong>현재: 로그인됨</strong> — {data?.preferredUsername ?? '(이름 없음)'} · 소속 테넌트{' '}
      {data?.tenants?.length ? <code>{data.tenants.join(', ')}</code> : <strong>없음</strong>}
      {!data?.tenants?.length && (
        <div>
          <small>
            소속 테넌트가 없으면 주문 화면은 전부 403 이다. <Link to="/admin">관리자</Link> 에서
            테넌트를 만들거나 <Link to="/memberships">멤버십</Link> 에서 초대를 수락한다.
          </small>
        </div>
      )}
    </div>
  )
}

/**
 * 흐름도. **Mermaid 를 쓰지 않는다** — 이 콘솔에 렌더러를 넣으면 검증 장치가 라이브러리를
 * 하나 더 지게 되고, 그건 이 앱의 목적과 무관한 의존이다.
 */
const FLOW_DIAGRAM = `  브라우저                게이트웨이 (BFF)                  뒤쪽
  ────────                ────────────────                  ────
  SESSION 쿠키   ──▶   ① 세션에서 토큰을 꺼낸다
  (토큰 없음)          ② 인입 Authorization / Cookie
                          / X-Tenant-Id 를 지운다  ← 위조 방어
                       ③ 테넌트 게이트: 요청 테넌트가
                          토큰의 소속인가? (claim 만 본다)
                       ④ 검증된 X-Tenant-Id 를 넣는다
                       ⑤ 세션의 토큰을 Bearer 로 재주입
                                     │
                                     ├──▶ /api/**  ──▶ 다운스트림 (fine 인가)
                                     └──▶ /iam/**  ──▶ IAM (회원·테넌트 도메인)
                                                          │
                       Keycloak ◀── OIDC 표준만 ──┘        └──▶ Keycloak Admin API
                                   (게이트웨이)                   (IAM 만 · 봉인)`

const STEPS = [
  {
    title: '1. 가입',
    what: '로그인 없이 부를 수 있는 유일한 API',
    why: '공개 라우트 · CSRF 예외 · 전용 rate limit 이 한꺼번에 검증된다. 201 이 와도 Keycloak 반영은 아직이다.',
    to: '/register',
    linkLabel: '가입 화면으로',
  },
  {
    title: '2. 로그인',
    what: '게이트웨이가 세션을 만들고 토큰을 보관한다',
    why: '브라우저에는 쿠키만 남는다. 이후 모든 화면이 이 세션 위에서 돈다.',
    to: '/session',
    linkLabel: '세션 상태 보기',
  },
  {
    title: '3. 테넌트 만들기',
    what: '관리 API 로 테넌트를 생성한다',
    why: 'unigate-admin 역할이 없으면 403 이다. 생성 직후 상태는 PENDING 이고 group 프로비저닝은 outbox 를 거친다.',
    to: '/admin',
    linkLabel: '관리자 화면으로',
  },
  {
    title: '4. 멤버십 확인',
    what: '토큰 claim 과 도메인 목록을 나란히 본다',
    why: '이 둘이 어긋나는 순간이 가장 헷갈리는 지점이다. 수락해도 재로그인 전까지 claim 은 그대로다.',
    to: '/memberships',
    linkLabel: '멤버십 화면으로',
  },
  {
    title: '5. 테넌트로 호출',
    what: '소속 테넌트로 주문을 조회·생성한다',
    why: '같은 URL 에 테넌트는 헤더로만 전달된다. 비소속 테넌트를 넣으면 다운스트림에 닿기 전에 403 이어야 한다.',
    to: '/session',
    linkLabel: '세션 화면에서 테넌트 선택',
  },
  {
    title: '6. 경계 흔들어 보기',
    what: '헤더를 위조해 게이트가 지우는지 확인한다',
    why: '다운스트림이 실제로 받은 헤더를 되비춘다. 이 콘솔의 존재 이유에 가장 가까운 화면이다.',
    to: '/diagnostics',
    linkLabel: '진단 화면으로',
  },
]

const SCREEN_MAP = [
  { to: '/session', name: '세션 · 토큰', proves: '토큰에 무엇이 실려 있고 게이트가 무엇을 지웠는가' },
  { to: '/profile', name: '프로필', proves: '즉시 반영과 202(워커 반영)의 차이, 그리고 보상' },
  { to: '/memberships', name: '멤버십 · 초대', proves: '인가의 근거는 도메인 목록이 아니라 토큰 claim 이다' },
  { to: '/admin', name: '관리 — 테넌트 · 멤버', proves: '역할이 없으면 서버가 막는다(FE 가 숨기는 게 아니라)' },
  { to: '/diagnostics', name: '진단 — 위조 헤더', proves: '인입 신뢰 헤더는 제거되고 검증값만 재주입된다' },
  { to: '/register', name: '가입', proves: '공개 경로의 인증·CSRF 예외와 rate limit' },
]
