import { useEffect, useState } from 'react'
import { NavLink, Route, Routes, useParams } from 'react-router-dom'
import { ensureCsrfToken, type CsrfToken } from './api/csrf'
import { toAbsolute } from './api/env'
import { ProofBanner } from './components/ProofBanner'
import { useSessionProbe } from './queries/hooks'
import { logoutJustRequested, markLogoutRequested } from './session/logoutNotice'
import { RequestInspector } from './components/RequestInspector'
import { Admin } from './pages/Admin'
import { Diagnostics } from './pages/Diagnostics'
import { Memberships } from './pages/Memberships'
import { OrderDetail } from './pages/OrderDetail'
import { Orders } from './pages/Orders'
import { Overview } from './pages/Overview'
import { Profile } from './pages/Profile'
import { Register } from './pages/Register'
import { Session } from './pages/Session'

function TenantNav() {
  const { tenantId } = useParams<{ tenantId: string }>()
  if (!tenantId) return null
  return (
    <span className="tenant">
      테넌트: <strong>{tenantId}</strong>
    </span>
  )
}

/**
 * 로그아웃은 **폼 POST** 다.
 *
 * `fetch` 로 보내면 302 연쇄의 끝(Keycloak end_session)을 XHR 이 따라가 CORS 에 막힌다.
 * 로그인과 같은 이유로 top-level 이동이어야 한다.
 *
 * ## 토큰을 기다린 뒤에야 누를 수 있게 한다
 * cross-origin 배포에서는 토큰이 **비동기**로 온다(`/csrf` 조회). 예전처럼
 * `{token && <input/>}` 로 두면 토큰이 없을 때 **필드 없는 폼이 그대로 제출되어 403** 이 난다.
 * 사용자에게는 "로그아웃을 눌렀는데 권한 없음" 이라는 설명 불가능한 화면이 뜬다.
 *
 * 그래서 토큰이 없으면 버튼을 **비활성**으로 둔다. 실패를 조용한 403 이 아니라
 * "아직 준비 안 됨" 이라는 눈에 보이는 상태로 바꾸는 것이다.
 */
function LogoutForm() {
  const [csrf, setCsrf] = useState<CsrfToken | null>(null)

  useEffect(() => {
    let alive = true
    ensureCsrfToken().then((t) => {
      if (alive) setCsrf(t)
    })
    return () => {
      alive = false
    }
  }, [])

  return (
    // 제출 **직전**에 표시를 남긴다. 이 폼은 top-level 이동이라 여기서부터 앱이 사라진다 —
    // 돌아왔을 때 "방금 로그아웃했다" 를 알 방법이 이것뿐이다(`session/logoutNotice.ts`).
    <form method="post" action={toAbsolute('/logout')} onSubmit={() => markLogoutRequested()}>
      {csrf && <input type="hidden" name={csrf.parameterName} value={csrf.token} />}
      <button type="submit" disabled={!csrf} title={csrf ? undefined : 'CSRF 토큰을 받는 중'}>
        로그아웃
      </button>
    </form>
  )
}

/**
 * 헤더의 인증 동작 — **세션 상태에 따라 로그인/로그아웃 중 하나만 보여준다.**
 *
 * ## 왜 바꿨나 (2026-08-02, alpha 관찰)
 * 예전에는 로그아웃 버튼이 **항상** 있었다. 그래서 로그아웃한 뒤에도 화면이 그대로였고, 본문은
 * "로그인하지 않은 상태다" 라고 말하는데 **헤더만 그걸 몰라** 우상단 「로그아웃」과 본문 「로그인」이
 * 동시에 보였다. 로그아웃이 됐는지 안 됐는지 화면으로 판단할 수 없었다.
 *
 * ## "역할로 숨기지 않는다" 원칙과 충돌하지 않는가
 * 충돌하지 않는다. 그 원칙은 **인가**에 대한 것이다 — 관리 메뉴를 역할로 감추면 "서버가 막았는가"
 * 를 확인할 수 없게 되므로 감추지 않는다(`pages/Admin.tsx`). 반면 여기서 갈리는 것은 **관측된
 * 세션 상태**이고, 서버 판정을 감추는 게 아니라 **드러난 사실을 반영**하는 것이다.
 *
 * 다만 대가는 있다 — "미인증으로 로그아웃을 눌러 본다" 는 경로가 화면에서 사라진다. 그 검증은
 * 진단 화면이나 직접 호출로 대신한다.
 *
 * ## 상태를 판단할 수 없을 때는 고르지 않는다
 * 5xx·전송 실패면 인증 여부를 모른다. 그때 둘 중 하나를 임의로 고르면 화면이 **거짓을 말한다.**
 * 모른다고 적고, 복구 수단인 로그아웃만 남긴다.
 */
function SessionActions() {
  const { state, loginUrl } = useSessionProbe()

  if (state === 'loading') return <span className="muted">세션 확인 중…</span>

  if (state === 'anonymous') {
    return (
      <button
        // ⚠️ 반드시 **주소창 이동**이어야 한다. fetch 로 보내면 302 를 따라간 Keycloak 응답이
        // CORS 에 막혀 콘솔에는 "CORS 에러" 만 뜨고 진짜 원인(미인증)이 가려진다(CLAUDE.md §6.1).
        onClick={() => loginUrl && (window.location.href = toAbsolute(loginUrl))}
        // 경로를 하드코딩하지 않는다 — 서버가 401 본문으로 알려준 값만 쓴다.
        disabled={!loginUrl}
        title={loginUrl ? undefined : '서버가 loginUrl 을 주지 않았다'}
      >
        로그인
      </button>
    )
  }

  if (state === 'unknown') {
    return (
      <>
        <span className="muted">세션 상태 불명</span>
        <LogoutForm />
      </>
    )
  }

  // 'authenticated' · 'authenticated-no-probe' — 둘 다 인증된 상태다(404 는 프로브 부재).
  return <LogoutForm />
}

/**
 * 로그아웃 직후 안내.
 *
 * **두 조건이 함께 참일 때만** 띄운다 — ① 이 탭에서 로그아웃을 요청했고 ② 지금 실제로 미인증이다.
 * ①만 보고 띄우면 로그아웃이 중간에 실패했을 때도 "로그아웃됐다" 고 말하게 된다.
 */
function LogoutNotice() {
  const { state } = useSessionProbe()
  if (!logoutJustRequested || state !== 'anonymous') return null
  return (
    <div className="note" role="status">
      <strong>로그아웃됐다.</strong> 게이트웨이 세션과 Keycloak SSO 세션이 함께 종료됐다 —
      다음 로그인은 비밀번호를 다시 묻는다.
      <br />
      <small>
        게이트웨이 세션만 지우면 Keycloak 의 SSO 쿠키가 남아 다음 접근이{' '}
        <strong>비밀번호 없이 자동 완료</strong>된다. 그래서 <code>end_session</code> 까지 왕복한다.
      </small>
    </div>
  )
}

/**
 * 네비게이션은 **경계별로 묶는다.**
 *
 * 예전에는 헤더에 링크 6개가 평면으로 나열돼 있었다. 그러면 각 화면이 무엇을 검증하는지,
 * 서로 어떤 관계인지 알 수 없다 — "프로필" 과 "진단" 이 같은 무게로 보인다.
 *
 * 여기서는 **어느 경계를 건드리는가**로 묶는다. 그래야 "지금 나는 인증을 보고 있는가,
 * 인가를 보고 있는가, 관리 평면을 보고 있는가" 가 화면 밖에서도 드러난다.
 */
const NAV_GROUPS: Array<{
  title: string
  hint: string
  items: Array<{ to: string; label: string }>
}> = [
  {
    title: '둘러보기',
    hint: '로그인 없이 읽을 수 있다',
    items: [
      { to: '/', label: '개요' },
      { to: '/register', label: '가입' },
    ],
  },
  {
    title: '내 세션',
    hint: '인증 · 토큰이 말하는 나',
    items: [
      { to: '/session', label: '세션 · 토큰' },
      { to: '/profile', label: '프로필' },
      { to: '/memberships', label: '멤버십 · 초대' },
    ],
  },
  {
    title: '관리 평면',
    hint: 'IAM 도메인 — 역할이 없으면 403',
    items: [{ to: '/admin', label: '테넌트 · 멤버' }],
  },
  {
    title: '경계 검증',
    hint: '게이트가 실제로 막는지 흔들어 본다',
    items: [{ to: '/diagnostics', label: '진단 — 위조 헤더' }],
  },
]

export function App() {
  return (
    <div className="app">
      <header>
        <h1>unigate 검증 콘솔</h1>
        <div className="header-right">
          <TenantNav />
          <SessionActions />
        </div>
      </header>

      <div className="layout">
        <nav className="sidenav">
          {NAV_GROUPS.map((group) => (
            <div key={group.title} className="nav-group">
              <div className="nav-group-title">{group.title}</div>
              <div className="nav-group-hint">{group.hint}</div>
              <ul>
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      // `end` 가 없으면 "/" 링크가 모든 경로에서 활성으로 표시된다.
                      end={item.to === '/'}
                      className={({ isActive }) => (isActive ? 'active' : undefined)}
                    >
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          <div className="nav-group">
            <div className="nav-group-title">테넌트 작업</div>
            {/*
              고정 링크를 둘 수 없다 — 테넌트가 URL 파라미터이고, 그 SoT 가 URL 인 것이
              의도이기 때문이다(`tenant/useCurrentTenant.ts`). 진입점을 안내로 대신한다.
            */}
            <div className="nav-group-hint">
              테넌트는 URL 이 SoT 라 고정 링크가 없다. <NavLink to="/session">세션 · 토큰</NavLink>{' '}
              에서 소속 테넌트를 골라 들어간다.
            </div>
          </div>
        </nav>

        <main>
          {/* 어느 화면으로 돌아오든 보이도록 라우트 바깥에 둔다(착지는 콘솔 루트지만 고정은 아니다). */}
          <LogoutNotice />
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/session" element={<Session />} />
            <Route path="/profile" element={<Profile />} />
            <Route path="/memberships" element={<Memberships />} />
            <Route path="/admin" element={<Admin />} />
            <Route path="/diagnostics" element={<Diagnostics />} />
            <Route path="/register" element={<Register />} />
            <Route path="/t/:tenantId/orders" element={<Orders />} />
            <Route path="/t/:tenantId/orders/:id" element={<OrderDetail />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
      </div>

      {/* 화면과 무관하게 항상 붙어 있다 — 헷갈리는 순간은 대부분 일반 화면에서 생긴다. */}
      <RequestInspector />
    </div>
  )
}

/**
 * 없는 경로. **게이트웨이의 404 와 헷갈리지 않게 출처를 밝힌다** — 이 콘솔은 서버 응답을
 * 관찰하는 도구이므로, 클라이언트가 만든 404 를 서버 것처럼 보이게 두면 안 된다.
 */
function NotFound() {
  return (
    <section>
      <h2>이 콘솔에 없는 경로다</h2>
      <ProofBanner
        claim={
          <>
            이 404 는 <strong>브라우저 라우터가 만든 것</strong>이고 서버 응답이 아니다.
          </>
        }
        how={['아래 인스펙터를 열어 본다']}
        expect="이 화면 때문에 새로 찍힌 요청 기록이 없어야 한다 — 네트워크 요청 자체가 나가지 않았다."
      />
      <p>
        왼쪽 메뉴에서 화면을 고르거나 <NavLink to="/">개요</NavLink> 로 돌아간다.
      </p>
    </section>
  )
}
