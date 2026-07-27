package me.ramos.unigate.adapter.gatewayIn

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import me.ramos.unigate.application.auth.port.outbound.TokenVerifierPort
import me.ramos.unigate.domain.auth.model.AuthenticatedPrincipal
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * 테넌트 게이트 단위 테스트 (Phase 9f) — **게이트웨이의 첫 인가 로직**.
 *
 * ## 통합이 아니라 단위인 이유
 * 헤더 왕복을 통합으로 보려면 요청을 되비추는 다운스트림 stub 과 **인증된 세션**이 둘 다 필요한데,
 * 세션은 oauth2Login 왕복을 거쳐야 만들어진다. 필터가 하는 일은 헤더 조작과 목록 비교라
 * exchange 를 직접 만들어 보는 편이 정확하고 빠르다.
 *
 * 실제 브라우저 세션에서의 동작은 e2e 로 따로 확인한다.
 */
class TenantGateFilterTest :
  BehaviorSpec({
    fun filterWith(tenants: List<String>): TenantGateFilter {
      val verifier = mockk<TokenVerifierPort>()
      coEvery { verifier.verify(any()) } returns
        AuthenticatedPrincipal(
          subject = "sub-1",
          email = "carol@example.local",
          roles = listOf("unigate-user"),
          tenants = tenants,
          audiences = listOf("unigate-downstream-demo"),
        )

      val client = mockk<OAuth2AuthorizedClient>()
      every { client.accessToken } returns
        OAuth2AccessToken(
          OAuth2AccessToken.TokenType.BEARER,
          "raw-token",
          Instant.now(),
          Instant.now().plusSeconds(300),
        )

      val repository = mockk<ServerOAuth2AuthorizedClientRepository>()
      every {
        repository.loadAuthorizedClient<OAuth2AuthorizedClient>(any(), any(), any())
      } returns Mono.just(client)

      return TenantGateFilter(verifier, repository)
    }

    /** 필터를 실행하고 다운스트림에 전달된 exchange 를 돌려준다. */
    fun run(
      filter: TenantGateFilter,
      request: MockServerHttpRequest,
    ): ServerWebExchange? {
      var forwarded: ServerWebExchange? = null
      val chain =
        GatewayFilterChain { exchange ->
          forwarded = exchange
          Mono.empty()
        }
      filter
        .filter()
        .filter(MockServerWebExchange.from(request), chain)
        .contextWrite(
          ReactiveSecurityContextHolder.withSecurityContext(
            Mono.just(SecurityContextImpl(TestingAuthenticationToken("carol", "n/a"))),
          ),
        ).block()
      return forwarded
    }

    given("인입 X-Tenant-Id 를 실은 요청") {
      `when`("테넌트를 지정하지 않았으면") {
        val filter = filterWith(listOf("acme"))
        val forwarded =
          run(
            filter,
            MockServerHttpRequest
              .get("/api/echo")
              .header(TenantGateFilter.HEADER_TENANT_ID, "위조된-테넌트")
              .build(),
          )

        then("헤더가 **제거된 채** 통과한다") {
          // ⚠️ 이게 이 필터의 절반이다. 게이트를 거치지 않는 요청일수록 인입 헤더가 그대로
          // 흘러갈 위험이 크다 — "덮어쓰기" 로는 이 경로를 막지 못한다.
          forwarded?.request?.headers?.getFirst(TenantGateFilter.HEADER_TENANT_ID) shouldBe null
        }
      }

      `when`("소속 테넌트를 요청하면서 위조 헤더도 함께 보내면") {
        val filter = filterWith(listOf("acme"))
        val forwarded =
          run(
            filter,
            MockServerHttpRequest
              .get("/api/echo")
              .header(TenantGateFilter.HEADER_TENANT_ID, "위조된-테넌트")
              .header(TenantGateFilter.HEADER_REQUESTED_TENANT, "acme")
              .build(),
          )

        then("위조 값이 아니라 **검증된 값**이 주입된다") {
          forwarded?.request?.headers?.getFirst(TenantGateFilter.HEADER_TENANT_ID) shouldBe "acme"
        }
      }
    }

    given("소속 테넌트를 요청") {
      val filter = filterWith(listOf("acme", "globex"))

      `when`("여러 소속 중 하나를 지정하면") {
        val forwarded =
          run(
            filter,
            MockServerHttpRequest
              .get("/api/echo")
              .header(TenantGateFilter.HEADER_REQUESTED_TENANT, "globex")
              .build(),
          )

        then("그 테넌트가 주입돼 통과한다") {
          forwarded?.request?.headers?.getFirst(TenantGateFilter.HEADER_TENANT_ID) shouldBe "globex"
        }
      }
    }

    given("소속이 아닌 테넌트를 요청") {
      val filter = filterWith(listOf("acme"))

      `when`("게이트를 지나려 하면") {
        var status: HttpStatus? = null
        try {
          run(
            filter,
            MockServerHttpRequest
              .get("/api/echo")
              .header(TenantGateFilter.HEADER_REQUESTED_TENANT, "남의회사")
              .build(),
          )
        } catch (e: ResponseStatusException) {
          status = HttpStatus.valueOf(e.statusCode.value())
        }

        then("403 으로 거부된다 — 다운스트림에 닿지 않는다") {
          // 401 이 아니다. 누구인지는 안다 — 로그인해도 해결되지 않으므로 리다이렉트하면
          // 무한 로그인 루프가 된다(Phase 4 에서 정한 원칙).
          status shouldBe HttpStatus.FORBIDDEN
        }
      }
    }

    given("소속 테넌트가 하나도 없는 사용자") {
      val filter = filterWith(emptyList())

      `when`("테넌트를 지정해 요청하면") {
        var status: HttpStatus? = null
        try {
          run(
            filter,
            MockServerHttpRequest
              .get("/api/echo")
              .header(TenantGateFilter.HEADER_REQUESTED_TENANT, "acme")
              .build(),
          )
        } catch (e: ResponseStatusException) {
          status = HttpStatus.valueOf(e.statusCode.value())
        }

        then("거부된다 — 판단할 수 없으면 열어주지 않는다(fail-closed)") {
          status shouldBe HttpStatus.FORBIDDEN
        }
      }
    }
  })
