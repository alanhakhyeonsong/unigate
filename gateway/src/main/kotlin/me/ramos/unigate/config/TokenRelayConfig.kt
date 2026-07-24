package me.ramos.unigate.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository

/**
 * TokenRelay 를 위한 인프라 — 세션에 보관된 access token 을 다운스트림으로 전달할 때 쓴다.
 *
 * ## 무엇을 하는가
 *
 * 라우트의 `tokenRelay()` 필터(`TokenRelayGatewayFilterFactory`)는 요청마다
 * [ReactiveOAuth2AuthorizedClientManager] 를 통해 현재 사용자의 토큰을 얻어
 * `Authorization: Bearer <access-token>` 헤더로 다운스트림 요청에 붙인다.
 *
 * ## 왜 이 빈을 직접 만들어야 하는가
 *
 * TokenRelay 필터는 `ReactiveOAuth2AuthorizedClientManager` 빈을 **필수로** 요구한다
 * (없으면 `IllegalStateException: No ReactiveOAuth2AuthorizedClientManager bean was found`).
 * 그런데 Spring Boot 자동 구성은 이 버전에서 [org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService]
 * (InMemory) 까지만 만들고 **manager 는 만들지 않는다.** 그래서 직접 등록한다.
 *
 * ## 두 가지 manager 중 무엇을 고르나 (판단 기준)
 *
 * | | [DefaultReactiveOAuth2AuthorizedClientManager] | `AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager` |
 * |---|---|---|
 * | 컨텍스트 | **웹 요청**(세션·`ServerWebExchange`) | 백그라운드(요청 없음) |
 * | 토큰 출처 | [ServerOAuth2AuthorizedClientRepository] (=세션) | `...ClientService` (인메모리) |
 * | 용도 | BFF · TokenRelay | 스케줄러·머신 투 머신 |
 *
 * unigate 는 로그인한 사용자의 요청 흐름 안에서 토큰을 쓰므로 **Default(요청 기반)** 이 맞다.
 * 이 manager 는 우리가 [SecurityConfig] 에서 등록한 세션 기반 저장소
 * ([org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository])
 * 를 그대로 주입받아 **세션(Valkey)에서** 토큰을 읽는다.
 *
 * > exchange 는 어디서 오나: TokenRelay 필터는 `OAuth2AuthorizeRequest` 에 exchange 를 넣지 않는다.
 * > 대신 manager 가 **Reactor Context** 에서 `ServerWebExchange` 를 찾는다 — WebFlux 의
 * > `ServerWebExchangeReactorContextWebFilter` 가 채워둔 값이다. Servlet 의 ThreadLocal 대신
 * > Context 로 요청 범위 데이터를 나르는 [02](../../../../../../../../docs/learning/02-webflux-event-loop.md) 의
 * > 그 구조가 여기서도 쓰인다.
 *
 * ## refreshToken provider 를 넣는 이유 (핵심)
 *
 * provider 를 `authorizationCode()` 만으로 두면 **만료된 access token 을 갱신하지 못한다.**
 * access token 은 5분, 세션은 30분이라 그 사이 반드시 만료 구간이 생긴다
 * (`docs/learning/04-oauth2-authorization-code-bff.md` §6 에서 실측: 만료 69초 뒤에도 갱신 안 됨).
 *
 * `refreshToken()` 을 추가하면 manager 의 `authorize()` 가 만료를 감지했을 때
 * refresh token 으로 새 access token 을 받아오고, 그 결과를 **세션에 다시 저장**한다.
 * 즉 "누가 토큰을 갱신하는가"의 답이 바로 이 provider 다 — 토큰을 실제로 쓰는 TokenRelay 가 트리거한다.
 *
 * 실패 모드: `refreshToken()` 을 빼면 만료 후 다운스트림이 갑자기 401 을 내기 시작하고,
 * 세션은 살아 있어 재로그인 유도도 없다. 저부하 개발 중에는 5분 안에 요청이 끝나 드러나지 않다가
 * **오래 열어둔 탭**에서만 재현되는, 찾기 어려운 형태로 나타난다.
 */
@Configuration
class TokenRelayConfig {
  @Bean
  fun authorizedClientManager(
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
    authorizedClientRepository: ServerOAuth2AuthorizedClientRepository,
  ): ReactiveOAuth2AuthorizedClientManager {
    val authorizedClientProvider =
      ReactiveOAuth2AuthorizedClientProviderBuilder
        .builder()
        .authorizationCode()
        .refreshToken()
        .build()

    return DefaultReactiveOAuth2AuthorizedClientManager(
      clientRegistrationRepository,
      authorizedClientRepository,
    ).apply {
      setAuthorizedClientProvider(authorizedClientProvider)
    }
  }
}
