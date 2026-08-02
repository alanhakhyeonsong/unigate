import { useEffect, useState } from 'react'
import { NavLink, Route, Routes, useParams } from 'react-router-dom'
import { ensureCsrfToken, type CsrfToken } from './api/csrf'
import { toAbsolute } from './api/env'
import { ProofBanner } from './components/ProofBanner'
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
    <form method="post" action={toAbsolute('/logout')}>
      {csrf && <input type="hidden" name={csrf.parameterName} value={csrf.token} />}
      <button type="submit" disabled={!csrf} title={csrf ? undefined : 'CSRF 토큰을 받는 중'}>
        로그아웃
      </button>
    </form>
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
          <LogoutForm />
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
