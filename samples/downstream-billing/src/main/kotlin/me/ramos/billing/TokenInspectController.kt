package me.ramos.billing

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 도착한 토큰이 **무엇을 담고 있는지** 되비춘다. 이 샘플의 관측 수단이다.
 *
 * `downstream-demo` 의 `/echo` 가 "무슨 헤더가 왔나" 를 본다면, 여기는 "그 토큰이 **누구를 향하고
 * 무엇을 알고 있나**" 를 본다. 다운스트림이 둘이 되어야 의미가 생기는 두 가지를 드러낸다:
 *
 * ## ① 공유 audience (§6.2 (a))
 * `audience` 필드에 `unigate-downstream-demo` **와** `unigate-billing-demo` 가 **함께** 찍힌다.
 * Keycloak 이 두 mapper 를 모두 GW client 의 dedicated scope 에 붙였기 때문이다
 * (`setup-realm.sh`). 곧 **하나의 토큰이 두 서비스 모두에서 유효**하다는 뜻이고,
 * A 를 침해한 쪽이 그 토큰으로 B 를 그대로 호출할 수 있다.
 *
 * 다운스트림이 하나일 때는 이 배열의 길이가 1 이라 아무 문제로 보이지 않는다.
 * **2대째가 되어야 값이 눈에 보이는 형태로 드러난다.**
 *
 * ## ② claim 누출 (§4.1)
 * `tenantMemberships` 에 **사용자가 속한 모든 테넌트**가 찍힌다. 지금 요청한 테넌트뿐 아니라
 * 이 서비스와 아무 상관없는 소속까지. 청구 서비스가 알 필요가 없는 정보다.
 *
 * 다운스트림이 우리 팀 소유면 감수할 수 있지만, 파트너 코드가 섞이면 그건 누출이다.
 * realm 을 나눠야 풀리는 문제가 아니라 **릴레이 토큰의 claim 을 요청 스코프로 줄이면**(F2) 풀린다.
 *
 * > 토큰 **원문**은 싣지 않는다. `/echo` 는 로컬 검증용이라 그대로 노출하지만, 이 엔드포인트는
 * > 브라우저 화면에 뜨는 것을 전제로 하므로 `CLAUDE.md` §8("토큰을 로그·응답에 남기지 않는다")을
 * > 지킨다. 여기서 보고 싶은 것은 토큰 자체가 아니라 **그 안의 claim 분포**다.
 */
@RestController
class TokenInspectController {
    @GetMapping("/token")
    fun inspect(
        request: HttpServletRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): TokenInspectResponse =
        TokenInspectResponse(
            service = SERVICE_NAME,
            subject = jwt.subject,
            issuer = jwt.issuer?.toString(),
            // ① 이 배열의 길이가 2 이상이면 공유 aud 다.
            audience = jwt.audience,
            expectedAudience = EXPECTED_AUDIENCE_NOTE,
            // ② 요청 스코프와 대조해 보라 — 목록이 더 넓으면 그만큼이 누출이다.
            tenantMemberships = TenantScope.membershipsOf(jwt),
            requestedTenant = request.getHeader(TenantScope.HEADER_TENANT_ID),
        )

    companion object {
        private const val SERVICE_NAME = "downstream-billing"

        /**
         * 기대 audience 는 설정값이지만 여기서는 **설명 문자열**로만 둔다.
         * 실제 판정은 `AudienceValidator` 가 이미 끝냈고(여기 도달했다는 것 자체가 통과의 증거),
         * 이 응답의 목적은 "무엇을 기대했나" 가 아니라 "무엇이 실제로 실려 왔나" 다.
         */
        private const val EXPECTED_AUDIENCE_NOTE =
            "unigate.billing.expected-audience 로 주입 (기본 unigate-billing-demo)"
    }
}

data class TokenInspectResponse(
    val service: String,
    val subject: String,
    val issuer: String?,
    val audience: List<String>,
    val expectedAudience: String,
    val tenantMemberships: List<String>,
    val requestedTenant: String?,
)
