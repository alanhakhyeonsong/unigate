import http from 'k6/http'
import { check, fail } from 'k6'

/**
 * BFF 세션 로그인 자동화.
 *
 * ## 왜 Bearer 토큰으로는 안 되는가 (이 파일이 존재하는 이유)
 *
 * 게이트웨이는 **OAuth2 Client(BFF)** 다 — `oauth2Login` 만 설정돼 있고
 * Resource Server 가 아니다. 그래서 `Authorization: Bearer <token>` 을 붙여도
 * 게이트웨이는 그것을 **인증으로 취급하지 않는다.** 미인증으로 보고 401(XHR) 또는 302 를 낸다.
 *
 * Keycloak 에서 Direct Access Grants 로 토큰을 받아 오는 흔한 부하테스트 방식이
 * 여기서는 통하지 않는다는 뜻이다. 게이트웨이에 부하를 주려면 **세션 쿠키**가 필요하고,
 * 세션 쿠키를 얻으려면 Authorization Code 플로우를 실제로 통과해야 한다.
 *
 * ## 흐름
 *
 *   GET  {gw}/oauth2/authorization/keycloak
 *     → 302 Keycloak 로그인 페이지 (k6 가 자동 추종)
 *   POST <form action>  username/password
 *     → 302 {gw}/login/oauth2/code/keycloak?code=...
 *     → 302 {gw}/  + Set-Cookie: SESSION=...
 *
 * k6 는 VU 마다 독립 쿠키 jar 를 갖고 리다이렉트를 자동으로 따라가므로,
 * 이 함수가 끝나면 이후 요청에 세션이 자동으로 실린다.
 */

/** Keycloak 로그인 폼의 action URL 을 뽑는다. */
function extractLoginAction(html) {
  // Keycloak 테마마다 속성 순서가 달라 form 태그 전체에서 action 만 찾는다.
  const form = html.match(/<form[^>]*id="kc-form-login"[^>]*>/i)
  if (!form) return null
  const action = form[0].match(/action="([^"]+)"/i)
  if (!action) return null
  // HTML 엔티티를 되돌린다. `&amp;` 를 그대로 두면 쿼리 파라미터가 깨져
  // Keycloak 이 "We are sorry... invalid request" 를 낸다 — 증상만 보면 자격증명 문제로 오해한다.
  return action[1].replace(/&amp;/g, '&')
}

export function login(baseUrl, username, password) {
  const authorize = http.get(`${baseUrl}/oauth2/authorization/keycloak`, {
    tags: { name: 'auth:authorize' },
  })

  // 이미 세션이 있으면 Keycloak 이 로그인 폼 없이 바로 되돌린다(SSO). 그 경우도 성공이다.
  const action = extractLoginAction(authorize.body || '')
  if (!action) {
    if (authorize.status === 200 && !/kc-form-login/.test(authorize.body || '')) {
      return true
    }
    fail(`로그인 폼을 찾지 못했습니다 (status=${authorize.status}). Keycloak 응답을 확인하세요.`)
  }

  const res = http.post(
    action,
    { username: username, password: password },
    { tags: { name: 'auth:login' } },
  )

  const ok = check(res, {
    '로그인 후 게이트웨이로 돌아왔다': (r) => r.url.startsWith(baseUrl),
    '로그인 폼이 다시 나오지 않았다': (r) => !/kc-form-login/.test(r.body || ''),
  })

  if (!ok) {
    // 자격증명 오류와 realm 설정 오류를 구분해야 원인을 좁힐 수 있다.
    const reason = /Invalid username or password/i.test(res.body || '')
      ? '자격증명 불일치'
      : '리다이렉트 설정(redirect_uri) 또는 realm 구성 확인 필요'
    fail(`로그인 실패 (${reason}) status=${res.status}`)
  }
  return true
}

/**
 * CSRF 토큰을 꺼낸다.
 *
 * 게이트웨이는 CSRF 를 **켜 둔다**(쿠키로 인증하므로 공격 표면이 실재한다).
 * GET 은 무관하지만 **인증된 POST 는 토큰 없이 전부 403** 이다.
 * 쿠키 이름과 헤더 이름이 다르다는 점에 주의 — 잘못 쓰면 증상이 같은 403 이라 구분되지 않는다.
 */
export function csrfHeader(baseUrl) {
  const jar = http.cookieJar()
  const cookies = jar.cookiesForURL(baseUrl)
  const token = cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : null
  return token ? { 'X-XSRF-TOKEN': token } : {}
}
