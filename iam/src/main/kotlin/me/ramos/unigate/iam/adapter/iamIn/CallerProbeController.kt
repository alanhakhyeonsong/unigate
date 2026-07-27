package me.ramos.unigate.iam.adapter.iamIn

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * local 전용 진단 프로브 — **GW → IAM token relay 가 실제로 이어지는지** 눈으로 확인한다 (Phase 8f).
 *
 * `ThreadProbeController`(VT 확인)와 같은 성격이다. 기능이 아니라 검증·학습 목적이며
 * `@Profile("local")` 이라 다른 프로파일에는 라우트 자체가 존재하지 않는다.
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
@Profile("local")
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
