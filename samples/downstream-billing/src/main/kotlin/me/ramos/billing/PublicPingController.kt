package me.ramos.billing

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **검증 도구다. 기능이 아니다.** (`downstream-demo` 의 같은 클래스와 같은 이유)
 *
 * 게이트웨이를 우회한 직접 호출을 브라우저로 재현하려면 `localhost:8082` **origin 을 가진 페이지**가
 * 하나 필요하다. 모든 경로가 401 이면 브라우저는 `chrome-error://`(origin `null`)에 머물고,
 * 거기서 보낸 요청은 CORS 에 막혀 **인가 판단에 도달하지도 못한다** — "다운스트림이 막았다" 와
 * "브라우저가 막았다" 가 구분되지 않는다.
 *
 * 응답에 서비스 이름을 실어 **어느 다운스트림에 닿았는지**를 구분한다. 2대가 되면서 새로 필요해진
 * 필드다 — demo 와 billing 을 헷갈리면 라우트 검증 결과를 통째로 오독한다.
 */
@RestController
class PublicPingController {
    @GetMapping("/public/ping")
    fun ping(): Map<String, String> =
        mapOf(
            "status" to "ok",
            "service" to "downstream-billing",
            "port" to "8082",
        )
}
