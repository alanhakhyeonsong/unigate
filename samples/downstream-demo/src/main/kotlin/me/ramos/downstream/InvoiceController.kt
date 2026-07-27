package me.ramos.downstream

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **일부러 아무 검사도 하지 않는 엔드포인트다.** 기능이 아니라 증명 수단이다.
 *
 * "새 엔드포인트를 만들며 테넌트 검사를 잊었다" 는 상황을 그대로 재현한다. P9g 시점이었다면
 * 게이트웨이를 우회한 위조 `X-Tenant-Id` 가 여기서 **200 으로 통과**했을 것이다 —
 * `/echo` 가 실제로 그랬다.
 *
 * 지금은 `anyRequest` 인가 규칙이 문 앞에서 막는다. 이 컨트롤러가 **한 줄도 바뀌지 않은 채**
 * 보호되는 것이 default-deny 의 요점이다.
 */
@RestController
@RequestMapping("/invoices")
class InvoiceController {
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): Map<String, String> =
        mapOf(
            "id" to id,
            "note" to "이 엔드포인트는 테넌트 검사를 하지 않는다 — 그런데도 뚫리지 않는지 보는 것이 목적이다",
        )
}
