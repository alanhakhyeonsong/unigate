package me.ramos.downstream

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 다운스트림의 fine 인가 예시 (Phase 9g).
 *
 * ## 이 컨트롤러에 인가 코드가 없다는 것이 요점이다
 * 처음 버전에는 여기에 두 가지가 손으로 들어 있었다 — ① 헤더를 토큰과 대조 ② 자원의 소유
 * 테넌트와 대조. 둘 다 **잊으면 조용히 뚫리는** 코드였다. 지금은 각각 옮겼다.
 *
 * ```
 * ① coarse 재확인 → TenantContextAuthorizationManager (anyRequest 규칙)
 * ② 자원 격리     → OrderRepository (테넌트 없는 질의를 제공하지 않는다)
 * ```
 *
 * 컨트롤러에 남은 것은 **찾지 못했을 때 404** 하나뿐이다. 새 엔드포인트를 여기 추가해도
 * ①은 자동으로 적용되고, ②는 저장소를 쓰는 한 빠뜨릴 수 없다.
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val orders: OrderRepository,
) {
    @GetMapping
    fun list(tenant: TenantContext): List<Order> = orders.findAll(tenant)

    /**
     * 생성 — **요청 DTO 에 `tenantId` 가 없다.**
     *
     * 없는 것이 요점이다. 클라이언트가 소유 테넌트를 말할 자리를 두지 않으면 "본문과 헤더가
     * 다르면 어느 쪽을 믿나" 라는 문제가 생기지 않는다. 검사를 잘 하는 대신 **검사할 것이
     * 없게** 만든다(`docs/learning/20` 과 같은 방향).
     *
     * 클라이언트가 본문에 `tenantId` 를 실어 보내도 이 DTO 에 담길 자리가 없어 무시된다.
     */
    @PostMapping
    fun create(
        @RequestBody request: CreateOrderRequest,
        tenant: TenantContext,
    ): ResponseEntity<Order> = ResponseEntity.status(HttpStatus.CREATED).body(orders.create(tenant, request.item))

    /** 수정 — 범위 밖이면 404. 읽기와 같은 규칙이다. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: UpdateOrderRequest,
        tenant: TenantContext,
    ): ResponseEntity<Any> =
        orders.updateItem(tenant, id, request.item)?.let { ResponseEntity.ok<Any>(it) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf(
                    "status" to 404,
                    "reasonCode" to "order_not_found",
                    "detail" to "주문을 찾을 수 없습니다",
                ),
            )

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
        // 헤더가 아니라 **검증된 값**을 받는다. 이 타입이 존재한다는 것이 검증의 증거다.
        tenant: TenantContext,
    ): ResponseEntity<Any> {
        val order =
            orders.findById(tenant, id)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    mapOf(
                        "status" to 404,
                        "reasonCode" to "order_not_found",
                        "detail" to "주문을 찾을 수 없습니다",
                    ),
                )
        return ResponseEntity.ok(order)
    }
}

/**
 * 생성 요청 — **`tenantId` 필드가 없다.**
 *
 * 자원의 소유 테넌트는 언제나 검증된 [TenantContext] 에서 온다. 이 DTO 에 `tenantId` 를 두는
 * 순간 "본문이 말하는 테넌트" 라는 신뢰할 수 없는 입력이 생기고, 그때부터는 헤더와 본문을
 * 대조하는 코드를 **모든 쓰기 엔드포인트마다** 잊지 않고 넣어야 한다.
 */
data class CreateOrderRequest(
    val item: String,
)

data class UpdateOrderRequest(
    val item: String,
)
