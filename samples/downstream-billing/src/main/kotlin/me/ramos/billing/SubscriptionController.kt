package me.ramos.billing

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * ⚠️ **일부러 취약하다. 복사하지 말 것.** (`samples/README.md` §3)
 *
 * `paas-iam-scope-review.md` §5.5.4 가 코드 조각 한 줄로 예고한 그것을 실제로 구현한다:
 *
 * ```kotlin
 * // ❌ 그럴듯하고, 테스트도 통과하고, 교차 테넌트 접근이 열린다
 * if (jwt.tenants.contains(resource.tenantId)) allow()
 * ```
 *
 * ## 왜 이게 "리뷰에서 가장 안 잡히는 부류" 인가
 * 두 검사가 **각자 자기 몫은 다 했다.**
 *
 * | 검사 | 무엇을 물었나 | 판정 |
 * |---|---|---|
 * | GW `TenantGateFilter` | "요청한 테넌트(acme)의 멤버인가" | ✅ 맞다 → 통과 |
 * | 여기 | "이 자원의 테넌트(globex)에 소속돼 있나" | ✅ 맞다 → 통과 |
 *
 * 그런데 **합치면 구멍이다.** 사용자는 acme 컨텍스트로 로그인했는데 globex 자원을 읽었다.
 * 어느 쪽도 단독으로는 틀리지 않아서, 파일 하나만 보는 리뷰로는 잡히지 않는다.
 *
 * ## 재현
 * acme·globex 양쪽에 속한 사용자로 로그인한 뒤 acme 컨텍스트로:
 * ```
 * GET /api/billing/subscriptions/sub-b-1   (sub-b-1 은 globex 자원)  → 200 ❌
 * GET /api/billing/scoped/subscriptions/sub-b-1                      → 403 ✅
 * ```
 * 같은 토큰·같은 헤더·같은 자원인데 판정 근거만 다르다. 그 차이가 전부다.
 *
 * ## 구조적 해법
 * 규약(§5.5.4 규약 1)으로 "토큰 소속 목록을 인가 입력으로 쓰지 말라" 고 적을 수도 있지만,
 * **실을 게 없는 편이 강하다** — relay 토큰에 현재 스코프 하나만 실리면(F2 · §6.2 (b)/(c))
 * 이 코드는 애초에 쓸 수가 없다.
 */
@RestController
@RequestMapping("/subscriptions")
class SubscriptionController(
    private val repository: SubscriptionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{id}")
    fun findOne(
        @PathVariable id: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): SubscriptionResponse {
        val subscription =
            repository.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "구독을 찾지 못했다")

        // ❌ 여기가 그 한 줄이다. 토큰의 **소속 목록**과 비교한다.
        val memberships = TenantScope.membershipsOf(jwt)
        if (subscription.tenantId !in memberships) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "소속 밖 테넌트의 구독이다")
        }

        log.warn(
            "취약 판정 통과 id={} resourceTenant={} tokenMemberships={} — 요청 스코프는 보지 않았다",
            id,
            subscription.tenantId,
            memberships,
        )
        return SubscriptionResponse.from(subscription, verdictBy = "token-memberships")
    }
}

/**
 * ✅ **대조군.** 같은 자원을 §5.5.4 규약대로 판정한다 — **검증된 스코프 헤더**와 비교한다.
 *
 * 차이는 딱 한 줄이다: 비교 대상이 `memberships`(목록) 이 아니라 `scope.tenantId`(단일 값) 다.
 * 목록과 비교하면 "속하기만 하면" 통과하고, 스코프와 비교하면 "지금 그 테넌트로 들어왔을 때만"
 * 통과한다. 소속 목록은 §5.5.4 규약 2 대로 **스코프 스위처용**이지 인가 입력이 아니다.
 */
@RestController
@RequestMapping("/scoped/subscriptions")
class ScopedSubscriptionController(
    private val repository: SubscriptionRepository,
) {
    @GetMapping("/{id}")
    fun findOne(
        @PathVariable id: String,
        scope: TenantScope,
    ): SubscriptionResponse {
        val subscription =
            repository.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "구독을 찾지 못했다")

        // ✅ 요청이 어느 테넌트 컨텍스트로 들어왔는지와 자원의 소유 테넌트를 맞춘다.
        if (subscription.tenantId != scope.tenantId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "요청 스코프의 자원이 아니다")
        }

        return SubscriptionResponse.from(subscription, verdictBy = "verified-scope-header")
    }

    /** 목록은 자원 접근 자체가 스코프로 좁혀진다 — 판정이 아니라 질의로 푸는 형태. */
    @GetMapping
    fun list(scope: TenantScope): List<SubscriptionResponse> =
        repository
            .findByTenant(scope.tenantId)
            .map { SubscriptionResponse.from(it, verdictBy = "verified-scope-header") }
}

data class SubscriptionResponse(
    val id: String,
    val tenantId: String,
    val plan: String,
    val monthlyFeeKrw: Long,
    /** 무엇을 근거로 통과시켰는지. 응답만 보고도 어느 판정 경로였는지 구분하기 위한 계측 필드다. */
    val verdictBy: String,
) {
    companion object {
        fun from(
            subscription: Subscription,
            verdictBy: String,
        ) = SubscriptionResponse(
            id = subscription.id,
            tenantId = subscription.tenantId,
            plan = subscription.plan,
            monthlyFeeKrw = subscription.monthlyFeeKrw,
            verdictBy = verdictBy,
        )
    }
}
