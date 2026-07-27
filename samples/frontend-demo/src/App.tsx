import { Link, Route, Routes, useParams } from 'react-router-dom'
import { readCsrfToken } from './api/csrf'
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
 */
function LogoutForm() {
  const token = readCsrfToken()
  return (
    <form method="post" action={toAbsolute('/logout')}>
      {token && <input type="hidden" name="_csrf" value={token} />}
      <button type="submit">로그아웃</button>
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
