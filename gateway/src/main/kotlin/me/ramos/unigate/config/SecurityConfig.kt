package me.ramos.unigate.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * Phase 1 진행용 임시 보안 설정.
 *
 * spring-boot-starter-oauth2-client 가 클래스패스에 있고 client registration 이 설정되면
 * Spring Security 가 기본 SecurityWebFilterChain 을 자동 구성한다. 그 기본값은
 * "모든 요청 인증 필요 + OAuth2 로그인 리다이렉트" 이므로, 이 클래스가 없으면
 * /actuator/health 조차 302 로 Keycloak 로그인으로 넘어간다.
 *
 * 지금은 라우팅·세션을 인증과 분리해 검증하기 위해 전부 permitAll 로 열어둔다.
 * Phase 1 의 OAuth2 로그인 단계에서 이 설정을 실제 인증 정책으로 교체한다.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
  environment: Environment,
) {
  init {
    // 주석만으로는 배포 사고를 막지 못한다. local 이 아니면 기동 자체를 실패시킨다.
    // 이 설정이 alpha 로 나가면 로그인 리다이렉트가 발생하지 않아 아무도 이상을
    // 눈치채지 못한 채 /api/** 가 인증 없이 열린다.
    require(environment.activeProfiles.contains(LOCAL_PROFILE)) {
      "SecurityConfig 는 Phase 1 진행용 임시 설정(anyExchange().permitAll())이다. " +
        "local 외 프로파일에서는 기동할 수 없다. " +
        "실제 인증 정책을 구현한 뒤 이 가드를 제거하라. (활성 프로파일: ${environment.activeProfiles.toList()})"
    }
  }

  @Bean
  fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
    http
      // TODO(Phase 1 - OAuth2 로그인 단계): 세션 쿠키 기반이므로 CSRF 정책을 다시 세운다.
      .csrf { it.disable() }
      .authorizeExchange { it.anyExchange().permitAll() }
      .build()

  companion object {
    private const val LOCAL_PROFILE = "local"
  }
}
