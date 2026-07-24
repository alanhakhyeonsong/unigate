package me.ramos.unigate.adapter.gatewayIn

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitSession
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * 로그인 결과가 **세션에 무엇으로 남았는지** 확인하는 로컬 전용 프로브.
 *
 * BFF 는 토큰을 브라우저에 주지 않기 때문에, 로그인이 성공했는지를 브라우저 화면만으로는
 * 알 수 없다(다운스트림 응답이 돌아오면 "됐나 보다" 수준). 그래서 세션에 담긴
 * 인증 주체와 authorized client 를 직접 들여다보는 창을 하나 만든다.
 *
 * 다음 단계(TokenRelay)의 사전 점검이기도 하다. `accessToken.present == false` 면
 * TokenRelay 는 붙여봐야 동작하지 않는다 — 릴레이할 토큰이 애초에 없다는 뜻이다.
 *
 * ⚠️ 토큰 원문은 응답에 담지 않는다. 여기서 access token 을 그대로 뱉으면
 * "브라우저에 토큰을 주지 않는다"는 BFF 의 전제를 프로브가 스스로 깨뜨린다.
 * 토큰 값 자체는 다운스트림 `/echo` 응답으로 확인한다(Step 6).
 */
@Configuration
@Profile("local")
class AuthProbeConfig(
  private val authorizedClientRepository: ServerOAuth2AuthorizedClientRepository,
) {
  @Bean
  fun authProbeRoutes(): RouterFunction<ServerResponse> =
    coRouter {
      GET("/debug/whoami") { request ->
        // `/debug/**` 는 permitAll 이므로 미인증 상태로도 들어올 수 있다.
        // Mono 가 비어 있는 것(= 인증 없음)과 값이 있는 것을 구분해야 하므로 awaitSingleOrNull 이다.
        val authentication =
          request
            .principal()
            .awaitSingleOrNull() as? Authentication

        val session = request.awaitSession()

        // WebFlux 는 익명 인증(anonymous)을 기본으로 켜지 않는다.
        // 즉 미인증이면 principal 이 아예 비어 있고, `authenticated == false` 인 토큰이 들어오지 않는다.
        if (authentication == null || !authentication.isAuthenticated) {
          return@GET ServerResponse.ok().bodyValueAndAwait(
            mapOf(
              "authenticated" to false,
              "sessionId" to session.id.take(SESSION_ID_PREFIX_LENGTH),
              "hint" to "브라우저로 /api/echo 에 접근해 로그인을 먼저 완료한다",
            ),
          )
        }

        val authorizedClient =
          authorizedClientRepository
            .loadAuthorizedClient<OAuth2AuthorizedClient>(
              KEYCLOAK_REGISTRATION_ID,
              authentication,
              request.exchange(),
            ).awaitSingleOrNull()

        ServerResponse.ok().bodyValueAndAwait(
          mapOf(
            "authenticated" to true,
            "principalName" to authentication.name,
            "authorities" to authentication.authorities.map { it.authority },
            "sessionId" to session.id.take(SESSION_ID_PREFIX_LENGTH),
            "idToken" to describeIdToken(authentication),
            "accessToken" to describeAccessToken(authorizedClient),
            // refresh token 유무가 곧 "세션 만료 전에 토큰을 갱신할 수 있는가"를 결정한다.
            "refreshTokenPresent" to (authorizedClient?.refreshToken != null),
          ),
        )
      }
    }

  /** id token 은 "누가 로그인했는가"의 증거다. 신원 식별에 필요한 최소 클레임만 노출한다. */
  private fun describeIdToken(authentication: Authentication): Map<String, Any?> {
    val oidcUser = authentication.principal as? OidcUser ?: return mapOf("present" to false)
    return mapOf(
      "present" to true,
      "sub" to oidcUser.subject,
      "preferredUsername" to oidcUser.preferredUsername,
      "expiresAt" to oidcUser.expiresAt?.toString(),
    )
  }

  /**
   * access token 은 **값을 빼고** 존재·만료·스코프만 보고한다.
   * 만료시각이 필요한 이유: 로그인 직후엔 정상이다가 몇 분 뒤 다운스트림이 401 을 내면
   * 원인이 "만료"인지 "aud 불일치"인지 갈린다.
   */
  private fun describeAccessToken(authorizedClient: OAuth2AuthorizedClient?): Map<String, Any?> {
    val accessToken = authorizedClient?.accessToken ?: return mapOf("present" to false)
    return mapOf(
      "present" to true,
      "tokenType" to accessToken.tokenType.value,
      "issuedAt" to accessToken.issuedAt?.toString(),
      "expiresAt" to accessToken.expiresAt?.toString(),
      "scopes" to accessToken.scopes,
    )
  }

  companion object {
    /** `application-local.yml` 의 `spring.security.oauth2.client.registration.<이 이름>` 과 일치해야 한다. */
    private const val KEYCLOAK_REGISTRATION_ID = "keycloak"

    /** 세션 ID 원문은 탈취되면 그대로 세션 하이재킹 수단이 된다. 동일성 비교에는 앞 8자면 충분하다. */
    private const val SESSION_ID_PREFIX_LENGTH = 8
  }
}
