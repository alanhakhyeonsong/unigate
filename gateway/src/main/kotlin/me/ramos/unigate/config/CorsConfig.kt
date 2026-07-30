package me.ramos.unigate.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

/**
 * CORS — **FE 를 게이트웨이와 다른 호스트에 둘 때만** 필요하다.
 *
 * ## 기본값은 "끔" 이다
 * 로컬은 Vite dev proxy 로 same-origin 을 유지하므로 CORS 가 필요 없다(`CLAUDE.md` §6.1).
 * 허용 origin 목록이 비어 있으면 이 필터는 아무것도 하지 않는다 — **켜는 것이 명시적 선택**이어야
 * 한다. 기본으로 열어두면 "왜 열려 있는지 아무도 모르는 CORS" 가 남는다.
 *
 * ## 왜 same-origin 을 못 쓰는 배포가 있나
 * alpha 는 FE 콘솔과 게이트웨이에 **각각 다른 호스트**를 쓰기로 했다(운영 분리). 그러면
 * 브라우저 입장에서 cross-origin 이라 preflight 와 `Allow-Credentials` 가 필요하다.
 *
 * ## 쿠키가 실리는 조건 — 세 가지가 모두 맞아야 한다
 * ```
 * ① 서버: Access-Control-Allow-Credentials: true
 * ② 서버: Access-Control-Allow-Origin 에 **정확한 origin** (와일드카드 불가 — credentials 와 함께 못 쓴다)
 * ③ 클라이언트: fetch(..., { credentials: 'include' })
 * ```
 * 하나라도 빠지면 **preflight 는 통과하는데 쿠키만 안 실려** 매 요청이 401 이 된다.
 * 증상이 "인증이 안 된다" 하나로 같아서 어느 조각이 빠졌는지 응답만 봐서는 구분되지 않는다.
 *
 * > SameSite 는 별개 문제다. FE 와 GW 가 **같은 사이트**(같은 등록가능 도메인)면 `Lax` 로도
 * > 쿠키가 실린다. 완전히 다른 도메인에 두면 `SameSite=None; Secure` 가 추가로 필요하고,
 * > 그건 CSRF 방어 전제를 약화시키므로 그때는 배치 자체를 다시 생각해야 한다.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties::class)
class CorsConfig {
  private val log = LoggerFactory.getLogger(javaClass)

  @Bean
  fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
    val source = UrlBasedCorsConfigurationSource()
    if (properties.allowedOrigins.isEmpty()) {
      log.info("CORS 비활성 — 허용 origin 이 없다(same-origin 배치로 간주)")
      return source
    }

    log.info("CORS 활성 — 허용 origin {}개", properties.allowedOrigins.size)
    val config =
      CorsConfiguration().apply {
        // ⚠️ **정확한 origin 만** 넣는다. `addAllowedOriginPattern("*")` 은 credentials 와 함께
        // 쓰면 어떤 사이트든 인증된 요청을 보낼 수 있게 되어 CSRF 방어를 무의미하게 만든다.
        allowedOrigins = properties.allowedOrigins
        allowCredentials = true
        allowedMethods = ALLOWED_METHODS
        // CSRF 토큰 헤더와 테넌트 주장 헤더가 여기 없으면 preflight 에서 막힌다.
        // `X-Tenant-Id` 는 **넣지 않는다** — 클라이언트가 보낼 값이 아니다(게이트가 주입한다).
        allowedHeaders = ALLOWED_HEADERS
        // 요청 헤더(위)와 **응답 헤더(아래)는 별개 목록**이다. 아래가 없으면 429 는 도착하는데
        // `Retry-After` 만 JS 에서 사라진다.
        exposedHeaders = EXPOSED_HEADERS
        maxAge = PREFLIGHT_CACHE_SECONDS
      }
    source.registerCorsConfiguration("/**", config)
    return source
  }

  private companion object {
    val ALLOWED_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

    /**
     * `X-XSRF-TOKEN` — CSRF 토큰(쿠키에서 읽어 헤더로 돌려보낸다, `CLAUDE.md` §6.1).
     * `X-Requested-Tenant` — 클라이언트의 테넌트 **주장**(검증은 게이트가 한다, Phase 9f).
     */
    val ALLOWED_HEADERS = listOf("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Requested-Tenant")

    /**
     * **응답** 헤더 중 JS 에 보여줄 것.
     *
     * cross-origin 에서 브라우저가 스크립트에 노출하는 응답 헤더는 CORS-safelisted 7개
     * (`Cache-Control` `Content-Language` `Content-Length` `Content-Type` `Expires`
     * `Last-Modified` `Pragma`)뿐이다. `Retry-After` 는 거기 없다.
     *
     * ⚠️ 빠뜨렸을 때의 증상이 고약하다 — 응답은 429 로 정상 도착하고 헤더도 실제로 붙어 있는데
     * `res.headers.get('Retry-After')` 만 **null** 이다. 에러도 경고도 없다. same-origin 인
     * 로컬(Vite dev proxy)에서는 잘 읽히므로 **alpha 같은 별도 호스트 배포에서만** 드러난다.
     * `RetryAfterFilter` 가 값을 계산해 붙여도 이 목록이 없으면 아무도 못 본다.
     */
    val EXPOSED_HEADERS = listOf("Retry-After")

    /** preflight 캐시 1시간. 매 요청마다 OPTIONS 왕복이 붙는 것을 막는다. */
    const val PREFLIGHT_CACHE_SECONDS = 3600L
  }
}

/**
 * 허용 origin 목록.
 *
 * **스킴+호스트+포트를 정확히** 적는다(`https://console.example.com`). 경로나 끝 슬래시를 붙이면
 * 브라우저가 보내는 `Origin` 헤더와 문자열이 달라 조용히 매칭에 실패한다.
 */
@ConfigurationProperties(prefix = "unigate.cors")
data class CorsProperties(
  val allowedOrigins: List<String> = emptyList(),
)
