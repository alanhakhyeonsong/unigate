import { useEffect, useState } from 'react'
import { Link, Route, Routes, useParams } from 'react-router-dom'
import { ensureCsrfToken, type CsrfToken } from './api/csrf'
import { toAbsolute } from './api/env'
import { Diagnostics } from './pages/Diagnostics'
import { Admin } from './pages/Admin'
import { Landing } from './pages/Landing'
import { Memberships } from './pages/Memberships'
import { OrderDetail } from './pages/OrderDetail'
import { Orders } from './pages/Orders'
import { Profile } from './pages/Profile'

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

export function App() {
  return (
    <div className="app">
      <header>
        <h1>unigate 검증 콘솔</h1>
        <nav>
          <Link to="/">세션</Link>
          <Link to="/profile">프로필</Link>
          <Link to="/memberships">멤버십</Link>
          <Link to="/admin">관리자</Link>
          <Link to="/diagnostics">진단</Link>
          <TenantNav />
          <LogoutForm />
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/memberships" element={<Memberships />} />
          <Route path="/admin" element={<Admin />} />
          <Route path="/diagnostics" element={<Diagnostics />} />
          <Route path="/t/:tenantId/orders" element={<Orders />} />
          <Route path="/t/:tenantId/orders/:id" element={<OrderDetail />} />
        </Routes>
      </main>
    </div>
  )
}
