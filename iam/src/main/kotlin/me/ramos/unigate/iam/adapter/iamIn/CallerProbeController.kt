package me.ramos.unigate.iam.adapter.iamIn

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
  fun whoAmI(authentication: JwtAuthenticationToken): Map<String, Any?> {
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
      "expiresAt" to jwt.expiresAt?.toString(),
    )
  }
}
