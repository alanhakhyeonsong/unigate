package me.ramos.billing

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException

/**
 * 게이트웨이가 **검증해서 넣어준** 현재 요청의 테넌트 스코프.
 *
 * `paas-iam-scope-review.md` §5.5.4 규약 1 — *자원의 테넌트는 **검증된 스코프 헤더**와 비교한다.
 * 토큰의 소속 목록과 비교하지 않는다.* 이 타입이 그 "검증된 스코프 헤더" 쪽이다.
 *
 * ## 왜 헤더를 여기서 **다시** 대조하는가
 * 게이트웨이의 `TenantGateFilter` 가 이미 대조했다. 그런데 게이트웨이를 우회하면 `X-Tenant-Id` 는
 * 그냥 클라이언트가 쓴 문자열이다(P9g 실측 · `samples/README.md` §3 `/echo` 항목). 그래서
 * 다운스트림도 토큰과 한 번 더 맞춘다 — 게이트는 "빨리 거절" 이지 최종 방어선이 아니다(`CLAUDE.md`).
 *
 * 이 재대조는 **"헤더가 토큰 소속 안에 있는가"** 까지만 본다. 자원이 그 테넌트 것인지는
 * 여기서 알 수 없다(자원을 모른다) — 그건 컨트롤러 몫이고, 바로 거기서 §5.5.4 가 갈린다.
 */
data class TenantScope(
    val tenantId: String,
) {
    companion object {
        const val HEADER_TENANT_ID = "X-Tenant-Id"

        private const val TENANT_GROUP_PREFIX = "/tenants/"

        /** 토큰이 말하는 **소속 전체**. §4.1 이 지적한 "전 테넌트 목록" 이 바로 이 값이다. */
        fun membershipsOf(token: Jwt): List<String> =
            token
                .getClaimAsStringList("groups")
                .orEmpty()
                .filter { it.startsWith(TENANT_GROUP_PREFIX) }
                .map { it.removePrefix(TENANT_GROUP_PREFIX) }
    }
}

/**
 * 컨트롤러 파라미터로 [TenantScope] 를 받게 해준다.
 *
 * ⚠️ `@JvmInline value class` 로 만들면 안 된다 — 컴파일되면 파라미터 타입이 `String` 으로 펴져
 * `parameterType == TenantScope::class.java` 가 영원히 거짓이 되고 500 이 난다.
 * `downstream-demo` 의 `TenantContext` KDoc 에 겪은 실패가 기록돼 있다.
 */
class TenantScopeArgumentResolver : HandlerMethodArgumentResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == TenantScope::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: error("HttpServletRequest 를 얻지 못했다")

        val jwt =
            (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT 인증이 아니다")

        val requested = request.getHeader(TenantScope.HEADER_TENANT_ID)
        if (requested.isNullOrBlank()) {
            // 추측해서 채우지 않는다. 소속이 하나뿐인 사용자라도 "알아서 골라주면"
            // 그 규칙이 다음 사용자에게 잘못 적용된다(demo 와 같은 판단).
            log.warn("테넌트 헤더 없음 path={}", request.requestURI)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "X-Tenant-Id 가 없다")
        }

        val memberships = TenantScope.membershipsOf(jwt)
        if (requested !in memberships) {
            log.warn("헤더가 토큰의 소속과 불일치 header={} token={}", requested, memberships)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "요청 테넌트가 소속 밖이다")
        }

        return TenantScope(requested)
    }
}
