package me.ramos.downstream

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ⚠️ **일부러 취약하게 만든 엔드포인트다. 증명 수단이며 실제 코드가 아니다.**
 *
 * `/invoices`(읽기)가 "검사를 잊어도 default-deny 가 막아준다" 를 보였다면, 여기서는 그 반대를
 * 보인다 — **쓰기 경로는 default-deny 로 막히지 않는다.**
 *
 * ## 왜 막히지 않나
 * `TenantContextAuthorizationManager` 가 검증하는 것은 "`X-Tenant-Id` 가 **호출자의 소속인가**"
 * 하나다. 이 요청은 그 검사를 **정직하게 통과한다** — 호출자는 실제로 `acme` 소속이고 헤더도
 * `acme` 다. 그런데 자원의 소유 테넌트를 **본문**에서 가져오면 그 자원은 `globex` 것이 된다.
 *
 * ```
 * X-Tenant-Id: acme        ← 검증 통과 (진짜 소속이다)
 * { "tenantId": "globex" } ← 인가 계층은 본문을 보지 않는다
 * ```
 *
 * 인가는 "**어느 테넌트로 행동하는가**" 를 고정할 뿐, "**어느 테넌트의 자원을 만드는가**" 는
 * 모른다. 후자는 본문을 읽는 코드만 알 수 있다.
 *
 * ## 그래서 규약이 필요하다
 * 요청 DTO 에 `tenantId` 를 **두지 않는다**(`CreateOrderRequest` 참조). 클라이언트가 소유
 * 테넌트를 말할 자리가 없으면 이 취약점은 성립할 자리가 없다.
 */
@RestController
@RequestMapping("/legacy/orders")
class LegacyOrderController(
    private val orders: OrderRepository,
) {
    @PostMapping
    fun createFromBody(
        @RequestBody request: LegacyCreateOrderRequest,
        // ⚠️ tenant 를 받지만 **쓰지 않는다.** 실수를 그대로 재현한다.
        tenant: TenantContext,
    ): ResponseEntity<Order> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(orders.unsafeCreateWithExplicitTenant(request.tenantId, request.item))
}

/** ⚠️ `tenantId` 를 담는 순간 신뢰할 수 없는 입력이 하나 늘어난다. */
data class LegacyCreateOrderRequest(
    val tenantId: String,
    val item: String,
)
