package me.ramos.unigate.iam.adapter.iamIn

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 호출자 진단 프로브 — **GW → IAM token relay 가 실제로 이어지는지** 눈으로 확인한다 (Phase 8f).
 *
 * 기능이 아니라 검증·학습 목적이다.
 *
 * ## 왜 `@Profile("local")` 이 아니라 설정 스위치인가 (2026-08-02)
 * 원래 local 전용이었는데, **alpha 에서야 드러나는 문제가 실재**하기 때문에 그쪽에서도 켤 수
 * 있어야 했다 — 분리 배포의 CORS·착지·claim 전파는 로컬에서 재현되지 않는다(`docs/learning/42`).
 *
 * 프로파일 목록(`local, alpha`)에 이름을 박는 대신 [ConditionalOnProperty] 를 쓴 이유:
 * 환경이 하나 늘 때마다 **코드를 고쳐 재배포**해야 하고, 반대로 사고가 났을 때 **코드 배포 없이는
 * 끌 수 없다.** 진단용 엔드포인트는 끄는 비용이 싸야 한다.
 *
 * `matchIfMissing` 을 두지 않아 **설정이 없으면 빈이 아예 없다**(fail-closed). 켜는 것이 명시적
 * 행위여야 한다는 판단이며, 근거는 `docs/learning/36`.
 *
 * ## ⚠️ 이 스위치는 인증을 열지 않는다
 * 이 컨트롤러의 경로는 `/iam/debug` 하위라 `IamSecurityConfig` 의 `LOCAL_ONLY_PUBLIC_PATHS`
 * (= `/debug` 하위)에 **걸리지 않는다.** 따라서 `anyRequest().authenticated()` 가 적용되어
 * **켜져 있어도 인증이 필요하다.** 그리고 돌려주는 것은 호출자 **자기 토큰**의 클레임뿐이다.
 *
 * 같은 이유로 `ThreadProbeController` 는 `@Profile("local")` 로 **그대로 둔다** — 그쪽은
 * `/debug/thread` 라 local 에서 공개 경로이고, 스위치로 바꾸면 실수로 켰을 때 무인증으로 열린다.
 * 두 프로브의 취급이 다른 것은 일관성 부족이 아니라 **노출 범위가 다르기 때문**이다.
 *
 * > 위에서 경로를 "하위" 로 풀어 쓴 이유: KDoc 안에 슬래시 경로 뒤 와일드카드 두 개를 그대로
 * > 적으면 Kotlin 이 **중첩 블록 주석 시작**으로 읽어 파일 끝까지 주석이 된다(`CLAUDE.md` §5).
 * > 실제로 이 파일을 쓰다 한 번 걸렸다.
 *
 * ## 왜 필요한가 — 이 경로에는 조용히 깨질 지점이 많다
 * 브라우저에서 `GET /iam/debug/whoami` 하나가 성공하려면 아래가 **전부** 맞아야 한다.
 * 하나라도 틀리면 401/403 이 나는데, 어느 단계에서 틀렸는지는 응답만 봐서는 알 수 없다.
 *
 * 1. GW 세션에 access token 이 있다 (BFF 로그인 완료)
 * 2. GW `iam-authenticated` 라우트가 `tokenRelay()` 로 Bearer 를 주입했다
 * 3. IAM 이 Resource Server 로 서명·`iss`·`exp` 를 통과시켰다
 * 4. 토큰 `aud` 에 IAM 이 들어 있다 (realm 의 `iam-audience` 매퍼)
 *
 * 이 프로브는 통과한 뒤의 **토큰 내용**을 그대로 보여줘서, 특히 4번(`aud`)이 기대대로인지 확인하게 한다.
 * 매퍼를 안 넣었을 때의 증상이 "그냥 401" 이라 원인 추적이 어렵기 때문이다.
 *
 * ## 노출하지 않는 것
 * **토큰 원문·서명은 절대 반환하지 않는다**(`CLAUDE.md` §8). 식별에 필요한 클레임만 골라 담는다.
 * `sub`/`preferred_username` 는 이미 호출자 본인의 정보이므로 자기 자신에게 돌려주는 것은 무방하다.
 */
@RestController
@RequestMapping("/iam/debug")
@ConditionalOnProperty(
  prefix = "unigate.iam.probe.caller",
  name = ["enabled"],
  havingValue = "true",
)
class CallerProbeController {
  @GetMapping("/whoami")
  fun whoAmI(
    authentication: JwtAuthenticationToken,
    request: HttpServletRequest,
  ): Map<String, Any?> {
    val jwt: Jwt = authentication.token
    return mapOf(
      // GW 가 relay 한 토큰의 주체. IAM 도메인의 UserRef 와 이어지는 값이다.
      "subject" to jwt.subject,
      "preferredUsername" to jwt.getClaimAsString("preferred_username"),
      "email" to jwt.getClaimAsString("email"),
      // 검증을 통과한 이유를 드러내는 두 값. aud 에 IAM 이 없으면 애초에 여기 도달하지 못한다.
      "issuer" to jwt.issuer?.toString(),
      "audience" to jwt.audience,
      // `azp` = 이 토큰을 받아간 client. BFF 이므로 게이트웨이 로그인 client 여야 한다.
      // 여기에 다른 값이 찍히면 예상 밖의 경로로 토큰이 흘러들어온 것이다.
      "authorizedParty" to jwt.getClaimAsString("azp"),
      // Phase 9e: 소속 group 경로. GW 의 coarse 게이트(P9f)가 판단 근거로 삼을 값이라
      // **눈으로 확인할 수단**이 있어야 한다 — 이게 비면 게이트가 아무것도 통과시키지 못한다.
      //
      // `/tenants/` 접두사로 테넌트만 걸러낸다. realm 에는 테넌트가 아닌 group 도 있어
      // (`/unigate-users`) 전체를 그대로 쓰면 엉뚱한 group 을 테넌트로 오인한다.
      "groups" to jwt.getClaimAsStringList("groups"),
      "tenants" to
        jwt
          .getClaimAsStringList("groups")
          ?.filter { it.startsWith("/tenants/") }
          ?.map { it.removePrefix("/tenants/") },
      "expiresAt" to jwt.expiresAt?.toString(),
      // Phase 9f: **IAM 에 도달한** 테넌트 헤더. 여기서의 기대값은 언제나 `null` 이다.
      //
      // IAM 라우트에는 테넌트 게이트를 걸지 않는다(관리 평면은 대상 테넌트를 경로·본문으로 받는다).
      // 게이트를 안 거치는 경로일수록 인입 헤더가 그대로 흘러갈 위험이 크므로, GW 가 이 라우트에서
      // `X-Tenant-Id` 를 제거한다. 그 제거를 **눈으로 확인할 수단**이 없으면 라우트 설정이 빠져도
      // 아무 증상 없이 통과한다 — 위조 헤더는 조용히 성공하기 때문이다.
      //
      // 값이 찍히면 GW 라우트 설정에서 removeRequestHeader 가 빠진 것이다.
      "receivedTenantHeader" to request.getHeader("X-Tenant-Id"),
      // **대조군.** 위 필드가 `null` 인 것만으로는 "제거됐다" 와 "프로브가 애초에 헤더를 못 읽는다"
      // 가 구분되지 않는다. `X-Requested-Tenant` 는 제거 대상이 아니므로(클라이언트의 *주장*일 뿐
      // 신뢰 대상이 아니라 굳이 지울 이유가 없다) 여기엔 보낸 값이 그대로 찍혀야 한다.
      //
      // 두 필드를 나란히 보면 한 요청 안에서 **하나는 지워지고 하나는 통과한다** 는 것이 드러난다.
      "receivedRequestedTenantHeader" to request.getHeader("X-Requested-Tenant"),
    )
  }
}
