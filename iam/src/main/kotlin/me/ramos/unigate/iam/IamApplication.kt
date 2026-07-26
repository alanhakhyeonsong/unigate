package me.ramos.unigate.iam

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * unigate IAM 서비스 (Phase 8).
 *
 * 게이트웨이와 **별개의 애플리케이션**이다. 회원·프로필·역할·테넌트 도메인을 소유하고 Keycloak Admin API 를
 * 봉인한다(anti-corruption). 게이트웨이는 이 서비스를 **또 하나의 프록시 라우트**로 다룰 뿐이다
 * (`IAM_PLATFORM_DECISION.md` D4 — 모델 A).
 *
 * ## 스택이 게이트웨이와 정반대다
 * Servlet MVC + JPA + Virtual Thread. 근거는 `CLAUDE.md` §5.1 과 `IAM_PLATFORM_DECISION.md` §11.1.
 * 요약하면 **WebFlux 강제는 SCG 제약이었고 IAM 은 SCG 가 아니다.** Keycloak Admin client 가 블로킹이라
 * VT 쪽이 오히려 자연스럽다.
 *
 * ## 여기서 하지 않는 것
 * - **로그인/세션**: 게이트웨이(BFF)의 몫이다. IAM 은 Resource Server 로서 relay 된 JWT 를 검증만 한다.
 * - **OIDC 표준 호출**(discovery·JWKS·end_session): 게이트웨이 `keycloakOut` 담당.
 *   IAM 이 Keycloak 에 접근하는 것은 **Admin API 뿐**이며 자신의 service account 토큰을 쓴다(D7).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class IamApplication

fun main(args: Array<String>) {
  runApplication<IamApplication>(*args)
}
