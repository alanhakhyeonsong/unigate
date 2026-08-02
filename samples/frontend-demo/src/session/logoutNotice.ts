/**
 * "방금 로그아웃했다" 를 **페이지 로드를 건너 전달**하는 장치.
 *
 * ## 왜 React 상태로 안 되나
 * 로그아웃은 폼 POST 다. 게이트웨이 → Keycloak `end_session` → 콘솔로 이어지는 **top-level 이동**
 * 연쇄라, 돌아왔을 때 앱은 완전히 새로 시작한다. 메모리에 있던 것은 전부 사라진다.
 *
 * ## 왜 서버가 알려주지 않나
 * 서버가 `?logout` 같은 표시를 붙여 주려면 `post_logout_redirect_uri` 를 바꿔야 하는데, 그 값은
 * Keycloak client 의 `post.logout.redirect.uris` 와 **정확히 일치**해야 한다(`setup-realm.sh`).
 * 화면 문구 하나 때문에 realm 등록을 건드리는 것은 대가가 크고, 어긋나면 증상이
 * "로그아웃이 안 된다" 로만 보인다. 그래서 클라이언트가 스스로 기억한다.
 *
 * ## 왜 `sessionStorage` 인가
 * - `localStorage`: 탭을 닫았다 열어도 남아, 한참 뒤에 "로그아웃됐습니다" 가 뜬다
 * - `sessionStorage`: **같은 탭의 수명**에 묶이고 origin 을 떠났다 돌아와도 유지된다 — 정확히 필요한 범위
 *
 * ## ⚠️ 이건 "로그아웃 성공" 의 증거가 아니다
 * 여기 담기는 것은 **요청했다는 사실**뿐이다. 실제로 끝났는지는 세션 프로브가 판단한다. 그래서
 * 호출부는 이 값이 참이면서 **동시에 미인증으로 관측될 때만** 안내를 띄운다. 중간에 실패했다면
 * 여전히 인증 상태일 것이고, 그때 "로그아웃됐다" 고 말하면 거짓말이 된다.
 */

const KEY = 'unigate.logout-requested'

/** sessionStorage 가 막힌 환경에서도 앱이 죽지 않게 한다 — 이건 편의 기능이지 필수가 아니다. */
function safely<T>(fn: () => T, fallback: T): T {
  try {
    return fn()
  } catch {
    return fallback
  }
}

/** 로그아웃 폼을 제출하기 **직전**에 부른다. */
export function markLogoutRequested(): void {
  safely(() => sessionStorage.setItem(KEY, '1'), undefined)
}

/**
 * **모듈 로드 시점에 딱 한 번** 평가된다.
 *
 * 컴포넌트 안에서 소비하지 않는 이유: React StrictMode 는 개발 모드에서 마운트를 두 번 돌린다.
 * 그러면 첫 번째가 값을 지우고 두 번째는 `false` 를 봐서 안내가 **개발에서만** 안 뜬다 —
 * 프로덕션에서는 멀쩡한, 재현 조건이 정반대인 종류의 버그다. 모듈 스코프는 페이지 로드당
 * 한 번이라 그 함정이 성립하지 않는다.
 */
export const logoutJustRequested: boolean = safely(() => {
  const found = sessionStorage.getItem(KEY) !== null
  if (found) sessionStorage.removeItem(KEY)
  return found
}, false)
