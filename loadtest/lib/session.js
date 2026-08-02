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

  // ⚠️ **착지 URL 로 성공을 판정하지 않는다** (2026-08-02 에 이걸로 한 번 막혔다).
  //
  // 원래 판정은 `r.url.startsWith(baseUrl)` — "로그인 후 게이트웨이로 돌아왔다" 였다.
  // 그런데 FE 를 분리 배포하며 `UNIGATE_FRONTEND_BASE_URI` 가 들어오자(PR #47)
  // 로그인 성공 착지가 게이트웨이 루트가 아니라 **콘솔 호스트**가 됐다.
  // 로그인은 멀쩡히 성공하는데 부하테스트만 "로그인 실패" 로 죽었고,
  // 메시지가 redirect_uri·realm 을 가리켜 **엉뚱한 곳을 뒤지게** 만들었다.
  //
  // 착지는 배포 구성에 따라 바뀌는 값이다. 판정 근거는 그 구성과 무관한 것이어야 한다 —
  // BFF 로그인의 결과물은 **세션 쿠키**이므로 그걸 본다.
  const ok = check(res, {
    '로그인 폼이 다시 나오지 않았다': (r) => !/kc-form-login/.test(r.body || ''),
    '게이트웨이 세션 쿠키가 생겼다': () => hasSessionCookie(baseUrl),
  })

  if (!ok) {
    // 원인을 좁힐 수 있게 갈래를 나눈다. 착지 URL 을 메시지에 넣어 두면
    // 착지가 또 바뀌었을 때 로그만 보고 바로 알 수 있다.
    const reason = /Invalid username or password/i.test(res.body || '')
      ? '자격증명 불일치'
      : /kc-form-login/.test(res.body || '')
        ? '로그인 폼이 다시 나왔다 — 계정 상태(required action · temporary 비밀번호) 확인'
        : `세션 쿠키가 생기지 않았다 (착지: ${res.url})`
    fail(`로그인 실패 (${reason}) status=${res.status}`)
  }
  return true
}

/** 세션 쿠키 유무만 본다. `extractSessionCookie` 와 달리 없어도 fail 하지 않는다. */
function hasSessionCookie(baseUrl) {
  const cookies = http.cookieJar().cookiesForURL(baseUrl)
  return Boolean(cookies[SESSION_COOKIE] && cookies[SESSION_COOKIE][0])
}

/**
 * 로그인 후 세션 쿠키 **값**을 꺼낸다.
 *
 * ⚠️ **k6 의 쿠키 jar 는 VU 가 아니라 iteration 단위로 초기화된다.**
 * 그래서 "첫 반복에서만 로그인" 하는 흔한 패턴이 여기서는 통하지 않는다 —
 * 두 번째 반복부터 쿠키가 사라져 모든 요청이 401 을 받는다.
 *
 * 더 나쁜 것은 **증상이 조용하다는 점**이다. 요청은 계속 나가고 응답도 오므로
 * 처리량 그래프는 정상으로 보이는데, 실제로는 백엔드에 도달조차 못 한 요청을 세고 있다.
 * (실제로 한 번 겪었다: 429 는 0건인데 성공은 20건, 나머지는 전부 인증 실패였다.)
 *
 * 그래서 `setup()` 에서 한 번 로그인해 쿠키 값을 뽑아 두고,
 * 매 iteration 에서 `restoreSession()` 으로 jar 에 다시 심는다.
 */
export function extractSessionCookie(baseUrl) {
  const jar = http.cookieJar()
  const cookies = jar.cookiesForURL(baseUrl)
  const value = cookies[SESSION_COOKIE] ? cookies[SESSION_COOKIE][0] : null
  if (!value) {
    fail(
      `세션 쿠키(${SESSION_COOKIE})를 찾지 못했습니다. ` +
        `로그인은 성공했지만 쿠키 이름이 다를 수 있습니다: ${Object.keys(cookies).join(', ')}`,
    )
  }
  return value
}

/** 얻어 둔 세션 쿠키를 현재 iteration 의 jar 에 심는다. */
export function restoreSession(baseUrl, sessionValue) {
  http.cookieJar().set(baseUrl, SESSION_COOKIE, sessionValue)
}

// ── VU 스코프 세션 캐시 ────────────────────────────────────────────────────────
// k6 는 VU 마다 모듈을 독립 인스턴스화하므로, 모듈 최상위 변수는 **VU 스코프**다.
// iteration 을 넘어 유지되지만 VU 끼리는 섞이지 않는다 — 세션 보관에 딱 맞는 수명이다.
let cachedSession = null
let cachedUser = null

/**
 * 이 VU 의 세션을 보장한다. 처음이면 로그인하고, 이후에는 캐시된 쿠키를 다시 심는다.
 *
 * ## 왜 `setup()` 에서 한꺼번에 로그인하지 않는가
 *
 * 여러 사용자를 `setup()` 안에서 순차 로그인하면 **두 번째부터 실패한다.**
 * 첫 로그인 뒤 Keycloak 의 SSO 세션 쿠키가 jar 에 남아, 다음 `/oauth2/authorization/keycloak`
 * 요청이 로그인 폼을 거치지 않고 바로 통과해 버리기 때문이다. 그러면 폼을 찾지 못해
 * `status=404` 로 끊긴다(실제로 겪었다).
 *
 * VU 별로 로그인하면 이 문제가 구조적으로 사라진다 — VU 마다 jar 가 독립이라
 * 다른 사용자의 SSO 세션이 섞일 자리가 없다. 로그인 횟수는 VU 수만큼이지만
 * **VU 당 한 번**이라 IdP 부하는 미미하다.
 */
export function ensureSession(baseUrl, username, password) {
  if (cachedSession === null || cachedUser !== username) {
    login(baseUrl, username, password)
    cachedSession = extractSessionCookie(baseUrl)
    cachedUser = username
  }
  restoreSession(baseUrl, cachedSession)
}

/** Spring Session 의 기본 쿠키 이름. */
const SESSION_COOKIE = 'SESSION'

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
