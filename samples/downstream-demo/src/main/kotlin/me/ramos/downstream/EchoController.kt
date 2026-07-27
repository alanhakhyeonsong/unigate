package me.ramos.downstream

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

/**
 * 받은 요청을 그대로 되비추는 계측 엔드포인트.
 *
 * Phase 1 검증의 유일한 관측 수단이다.
 * - TokenRelay 가 실제로 Authorization 헤더를 붙였는지
 * - 위조된 인입 헤더가 게이트웨이에서 제거됐는지
 *
 * 로컬 검증 전용이므로 토큰 원문을 그대로 노출한다. 커밋 대상이 아니며 배포하지 않는다.
 */
@RestController
@RequestMapping("/echo")
class EchoController {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param delayMs 응답을 지연시킨다. 게이트웨이의 동시성 관찰용
     *   (요청이 오래 물려 있어야 이벤트 루프 스레드 수를 셀 수 있다).
     *   다운스트림은 Servlet 스택이라 여기서 블로킹해도 무방하다.
     */
    @GetMapping
    fun echo(
        request: HttpServletRequest,
        // Step 8 이후엔 인증을 통과해야만 여기 도달한다. 검증된 JWT 가 principal 로 주입된다.
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam(required = false, defaultValue = "0") delayMs: Long,
    ): EchoResponse {
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }

        val headers =
            request.headerNames
                .toList()
                .associateWith { request.getHeader(it) }

        log.info("echo 요청 수신: path={}, sub={}, headers={}", request.requestURI, jwt?.subject, headers.keys)

        return EchoResponse(
            method = request.method,
            path = request.requestURI,
            query = request.queryString,
            headers = headers,
            authorization = describeAuthorization(headers),
            // 검증된 토큰에서 뽑은 인증 주체. 401 이 아닌 200 이면 여기에 sub 가 찍힌다.
            principal = jwt?.subject,
        )
    }

    /**
     * Authorization 헤더를 사람이 읽을 수 있는 형태로 요약한다.
     * JWT 면 payload 를 디코딩해 sub/aud/iss 를 바로 확인할 수 있게 한다.
     */
    private fun describeAuthorization(headers: Map<String, String?>): AuthorizationInfo {
        val raw =
            headers.entries
                .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
                ?.value
                ?: return AuthorizationInfo(present = false)

        val token = raw.removePrefix("Bearer ").trim()
        val parts = token.split(".")
        if (parts.size != 3) {
            // JWT 형식이 아니다 — 위조 헤더 검증 시 이 경로로 떨어진다.
            return AuthorizationInfo(present = true, jwt = false, rawValue = raw)
        }

        return AuthorizationInfo(
            present = true,
            jwt = true,
            payload = decodeBase64Url(parts[1]),
        )
    }

    private fun decodeBase64Url(segment: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(segment)) }
            .getOrElse { "<디코딩 실패: ${it.message}>" }
}

data class EchoResponse(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String?>,
    val authorization: AuthorizationInfo,
    val principal: String? = null,
)

data class AuthorizationInfo(
    val present: Boolean,
    val jwt: Boolean? = null,
    val payload: String? = null,
    val rawValue: String? = null,
)
