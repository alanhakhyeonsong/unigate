package me.ramos.downstream

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.function.Supplier

/**
 * **검증을 마친** 테넌트. 이 타입의 인스턴스가 존재한다는 것 자체가 "토큰과 대조를 통과했다"는 뜻이다.
 *
 * 컨트롤러는 `X-Tenant-Id` 헤더를 **직접 읽지 않는다.** 읽는 순간 검증 여부가 코드에서
 * 구별되지 않기 때문이다 — 게이트웨이에서 `X-Requested-Tenant` 와 `X-Tenant-Id` 를 이름으로
 * 갈라 놓은 것(Phase 9f)과 같은 이유다. 여기서는 **타입**으로 가른다.
 *
 * ## ⚠️ `@JvmInline value class` 로 만들면 안 된다 (겪은 실패)
 * 값이 하나뿐이라 인라인 value class 가 어울려 보이고, 실제로 그렇게 썼다가 **500** 이 났다.
 * ```
 * NullPointerException: Parameter specified as non-null is null:
 *   method TenantContext.constructor-impl, parameter tenantId
 * ```
 * value class 는 컴파일되면 **파라미터 타입이 `String` 으로 펴진다.** 그래서 argument resolver 의
 * `parameterType == TenantContext::class.java` 가 영원히 거짓이 되고, Spring 은 그 자리를 평범한
 * 문자열 파라미터로 바인딩해 `null` 을 넣는다. 그 다음 인라인 클래스의 non-null 검사가 터진다.
 *
 * `docs/learning/20` §5 함정 1 이 **DI 대상**에 대해 똑같이 기록해 둔 것이다 —
 * "도메인 VO 에는 맞고 주입 대상에는 못 쓴다". 주입되는 자리에는 평범한 클래스를 쓴다.
 */
data class TenantContext(
    val tenantId: String,
) {
    companion object {
        /** 인가 단계에서 검증한 값을 컨트롤러까지 나르는 통로. 요청 범위다. */
        const val REQUEST_ATTRIBUTE = "unigate.tenantContext"

        const val HEADER_TENANT_ID = "X-Tenant-Id"

        private const val TENANT_GROUP_PREFIX = "/tenants/"

        /** 토큰이 말하는 소속 테넌트. **판단의 유일한 근거다.** */
        fun tenantsOf(token: org.springframework.security.oauth2.jwt.Jwt): List<String> =
            token
                .getClaimAsStringList("groups")
                .orEmpty()
                .filter { it.startsWith(TENANT_GROUP_PREFIX) }
                .map { it.removePrefix(TENANT_GROUP_PREFIX) }
    }
}

/**
 * Phase 9g 후속 — **잊으면 닫히는 기본값.**
 *
 * ## 왜 컨트롤러 안의 검사로는 부족한가
 * P9g 실측에서 확인했다: 게이트웨이를 우회하면 `X-Tenant-Id` 는 그냥 클라이언트가 쓴 값이다.
 * 그래서 다운스트림이 **토큰과 다시 대조**해야 하는데, 그 검사를 엔드포인트마다 손으로 넣으면
 * 새 엔드포인트에서 한 번 잊는 순간 **그 자리만 조용히 뚫린다.** 증상이 없다 —
 * 위조 헤더는 성공하기 때문이다.
 *
 * 그래서 검사를 컨트롤러가 아니라 **인가 규칙**으로 올린다. `anyRequest` 에 걸면 새 엔드포인트는
 * 아무것도 하지 않아도 보호받고, 테넌트와 무관한 경로만 **명시적으로** 예외가 된다.
 * 기본값이 안전한 쪽이고, 위험한 쪽이 눈에 보이는 한 줄을 요구한다.
 *
 * > P9c 에서 IAM 관리 API 를 엔드포인트가 아니라 **접두사 전체**로 막은 것과 같은 판단이다.
 * > "새 엔드포인트를 잊어도 안전하다" 는 성질을 얻는 게 요점이다.
 *
 * ## 무엇을 보나 (coarse 재확인)
 * ```
 * X-Tenant-Id ∈ 토큰의 groups 중 "/tenants/" 접두사   → 통과 + 검증된 값을 요청에 실어준다
 * 헤더 없음 / 불일치                          → 403
 * ```
 * 자원 소유권(fine)은 여기서 판단하지 않는다 — 자원을 모르기 때문이다. 그건 Repository 가 강제한다.
 */
class TenantContextAuthorizationManager : AuthorizationManager<RequestAuthorizationContext> {
    private val log = LoggerFactory.getLogger(javaClass)

    // Security 6.4+ 는 `authorize` 를 권장하고 `check` 를 deprecated 로 표시했지만, 이 버전에서
    // `check` 는 **여전히 abstract** 다(구현하지 않으면 컴파일 실패). 그래서 이쪽을 구현한다.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun check(
        authentication: Supplier<Authentication>,
        context: RequestAuthorizationContext,
    ): AuthorizationDecision {
        val auth = runCatching { authentication.get() }.getOrNull()
        if (auth == null || !auth.isAuthenticated) return AuthorizationDecision(false)

        // Resource Server 이므로 인증 주체는 JWT 다. 아니면 판단 근거가 없다 → 거부(fail-closed).
        val jwt = (auth as? JwtAuthenticationToken)?.token ?: return AuthorizationDecision(false)

        val requested = context.request.getHeader(TenantContext.HEADER_TENANT_ID)
        if (requested.isNullOrBlank()) {
            // 테넌트 범위 자원인데 범위가 없다. 추측해서 채우지 않는다 — 소속이 하나뿐인
            // 사용자라도 "알아서 골라주면" 그 규칙이 다음 사용자에게 잘못 적용된다.
            log.warn("테넌트 헤더 없음 path={}", context.request.requestURI)
            return AuthorizationDecision(false)
        }

        val tenants = TenantContext.tenantsOf(jwt)
        if (requested !in tenants) {
            // 게이트웨이를 우회한 위조 헤더가 여기서 죽는다.
            log.warn("헤더가 토큰의 소속과 불일치 header={} token={}", requested, tenants)
            return AuthorizationDecision(false)
        }

        // 통과한 값만 실어준다. 컨트롤러는 이 값 외에 테넌트를 알 방법이 없다.
        context.request.setAttribute(TenantContext.REQUEST_ATTRIBUTE, TenantContext(requested))
        return AuthorizationDecision(true)
    }
}

/**
 * 컨트롤러 파라미터로 [TenantContext] 를 받게 해준다.
 *
 * 값이 없다는 것은 **인가 규칙을 지나지 않고 여기 도달했다**는 뜻이다(= 예외 목록에 넣어 놓고
 * 테넌트를 요구했다). 조용히 null 을 주면 그 실수가 런타임에 데이터로 새므로 **즉시 터뜨린다.**
 */
class TenantContextArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == TenantContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)
        return request?.getAttribute(TenantContext.REQUEST_ATTRIBUTE) as? TenantContext
            ?: error(
                "TenantContext 가 없다 — 이 경로가 인가 규칙의 예외 목록에 들어 있으면서 " +
                    "테넌트를 요구하고 있다. SecurityConfig 를 확인할 것.",
            )
    }
}
