package me.ramos.unigate.adapter.gatewayIn

import kotlinx.coroutines.reactor.mono
import me.ramos.unigate.application.auth.port.outbound.TokenVerifierPort
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 테넌트 멤버십 게이트 — **게이트웨이의 첫 인가 로직** (Phase 9f).
 *
 * ## 무엇을 하나
 * ```
 * 인입 X-Tenant-Id  ──strip──>  (버린다. 절대 신뢰하지 않는다)
 * X-Requested-Tenant ──검증──>  대상 ∈ 토큰의 소속 테넌트?
 *                                 ├ 예   → 검증된 X-Tenant-Id 주입 → 다운스트림
 *                                 └ 아니오 → 403 (다운스트림 도달 전)
 * ```
 *
 * ## ⚠️ strip 이 이 필터의 절반이다
 * 인입 `X-Tenant-Id` 를 **무조건 제거**한다. 이것이 Phase 1 Step 7 의 `Authorization` 헤더 처리와
 * 같은 원칙이다 — 클라이언트가 보낸 신뢰 정보는 존재 자체를 지우고, **게이트웨이가 검증한 값만**
 * 다시 넣는다.
 *
 * "덮어쓰기" 로는 부족하다. 게이트를 통과하지 못한 요청(테넌트 무관 API 등)에서는 덮어쓸 값이
 * 없으므로 **인입 헤더가 그대로 흘러간다.** 그 순간 클라이언트가 헤더 하나로 남의 테넌트
 * 데이터를 읽는다. 제거와 덮어쓰기는 다른 연산이다.
 *
 * ## coarse 인가의 경계 — 도메인 조회를 하지 않는다
 * 판단 근거는 **토큰 claim 뿐**이다. "이 테넌트에 소속인가" 까지만 보고, "이 테넌트의 이 자원에
 * 접근 가능한가" 는 다운스트림의 몫이다(`IAM_PLATFORM_DECISION.md` §8).
 *
 * 여기서 IAM 에 멤버십을 물으면 **모든 요청이 IAM 호출을 동반**하고, 게이트웨이가 도메인을 아는
 * God-gateway 로 미끄러진다. 그 방지선이 "claim 만 본다" 는 규칙이다.
 *
 * ## 반영 지연을 인지할 것
 * 토큰의 테넌트 목록은 **발급 시점의 사실**이다. 멤버십이 해제돼도 이미 발급된 토큰은 만료
 * (현재 5분) 전까지 옛 소속을 담고 있어 이 게이트를 통과한다. 즉시 차단이 필요하면 별도 수단이
 * 필요하다(§14).
 */
@Component
class TenantGateFilter(
  private val tokenVerifier: TokenVerifierPort,
  private val authorizedClientRepository: ServerOAuth2AuthorizedClientRepository,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 라우트에 붙일 필터를 만든다.
   *
   * `GatewayFilter` 를 반환하는 팩토리 형태인 것은 **라우트별로 켜고 끄기 위해서**다.
   * `GlobalFilter` 로 만들면 actuator·fallback 까지 지나가고, 그 경로들은 테넌트와 무관하다.
   */
  fun filter(): GatewayFilter =
    GatewayFilter { exchange, chain ->
      // ⚠️ **가장 먼저** 인입 헤더를 지운다. 아래 어느 분기로 가든 이 제거는 유효해야 한다.
      val stripped = stripInboundTenantHeader(exchange)

      val requested = stripped.request.headers.getFirst(HEADER_REQUESTED_TENANT)
      if (requested.isNullOrBlank()) {
        // 테넌트를 지정하지 않은 요청이다. 게이트가 판단할 대상이 없으므로 통과시키되,
        // **인입 헤더는 이미 지워졌다** — 여기가 위조 헤더가 새어나갈 뻔한 자리다.
        return@GatewayFilter chain.filter(stripped)
      }

      resolveTenants(exchange)
        .flatMap { tenants ->
          if (requested in tenants) {
            log.debug("테넌트 게이트 통과 tenant={} 소속={}", requested, tenants.size)
            chain.filter(injectVerifiedTenant(stripped, requested))
          } else {
            // 403 이다. 401 이 아닌 이유: 누구인지는 안다. 로그인해도 해결되지 않으므로
            // 리다이렉트하면 무한 로그인 루프가 된다(Phase 4 에서 정한 원칙).
            log.warn("테넌트 게이트 거부 요청테넌트={} 소속아님", requested)
            Mono.error(
              ResponseStatusException(HttpStatus.FORBIDDEN, "요청한 테넌트에 소속되어 있지 않습니다"),
            )
          }
        }
    }

  /**
   * 세션의 access token 에서 소속 테넌트를 읽는다.
   *
   * ## 왜 [TokenVerifierPort] 를 쓰나 — 자기가 받은 토큰인데
   * Phase 2 에서 만들고 **소비자가 없던** 포트가 여기서 처음 쓰인다.
   *
   * 서명 검증이 중복처럼 보이지만 두 가지 이유로 그대로 둔다. 첫째, 세션 저장소(Valkey)가
   * 오염되는 경로를 배제할 수 없다 — 인가 판단의 입력은 검증된 값이어야 한다. 둘째, JWKS 가
   * 캐시돼 있어 **로컬 서명 검증**이라 비용이 작다(Phase 2 의 설계 목적이 정확히 이것이다).
   */
  private fun resolveTenants(exchange: ServerWebExchange): Mono<List<String>> =
    ReactiveSecurityContextHolder
      .getContext()
      .map { it.authentication }
      .flatMap { authentication -> loadAccessToken(authentication, exchange) }
      .flatMap { token -> mono { tokenVerifier.verify(token).tenants } }
      // 토큰을 못 얻으면 소속이 없는 것으로 본다 — **열어주지 않는다.**
      // 인가에서 "판단할 수 없음" 은 거부여야 한다(fail-closed).
      .defaultIfEmpty(emptyList())

  private fun loadAccessToken(
    authentication: Authentication?,
    exchange: ServerWebExchange,
  ): Mono<String> {
    if (authentication == null) return Mono.empty()
    return authorizedClientRepository
      .loadAuthorizedClient<org.springframework.security.oauth2.client.OAuth2AuthorizedClient>(
        KEYCLOAK_REGISTRATION_ID,
        authentication,
        exchange,
      ).mapNotNull { it.accessToken?.tokenValue }
  }

  /** 인입 `X-Tenant-Id` 를 **제거**한다(덮어쓰기가 아니다 — KDoc 참조). */
  private fun stripInboundTenantHeader(exchange: ServerWebExchange): ServerWebExchange =
    exchange
      .mutate()
      .request { it.headers { headers -> headers.remove(HEADER_TENANT_ID) } }
      .build()

  /** 게이트를 통과한 값만 주입한다. 다운스트림은 이 헤더만 신뢰한다. */
  private fun injectVerifiedTenant(
    exchange: ServerWebExchange,
    tenantId: String,
  ): ServerWebExchange =
    exchange
      .mutate()
      .request { it.header(HEADER_TENANT_ID, tenantId) }
      .build()

  companion object {
    /**
     * 다운스트림이 신뢰하는 헤더. **게이트웨이만 이 값을 쓴다.**
     * 인입된 것은 무조건 제거되므로, 다운스트림에 도달한 값은 반드시 검증을 거친 것이다.
     */
    const val HEADER_TENANT_ID = "X-Tenant-Id"

    /**
     * 클라이언트가 "어느 테넌트로 요청하는가" 를 밝히는 헤더.
     *
     * 이건 **주장일 뿐** 신뢰 대상이 아니다. 이름을 `X-Tenant-Id` 와 다르게 둔 것이 그 구분을
     * 드러낸다 — 같은 이름을 쓰면 "검증 전 값" 과 "검증 후 값" 이 코드에서 구별되지 않는다.
     */
    const val HEADER_REQUESTED_TENANT = "X-Requested-Tenant"

    private const val KEYCLOAK_REGISTRATION_ID = "keycloak"
  }
}
