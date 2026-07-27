package me.ramos.downstream

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * **검증 도구다. 기능이 아니다.**
 *
 * GW 우회 공격을 브라우저로 재현하려면 `localhost:8081` **origin 을 가진 페이지**가 하나 필요하다.
 * 다운스트림의 모든 경로가 401 이면 브라우저는 `chrome-error://` 페이지(origin `null`)에 머물고,
 * 거기서 보낸 요청은 cross-origin 이라 CORS 에 막혀 **인가 판단에 도달하지도 못한다.**
 * 그러면 "다운스트림이 막았다" 와 "브라우저가 막았다" 가 구분되지 않는다.
 *
 * 인증 없이 200 을 주는 경로를 하나 열어 그 origin 을 만든다. 토큰은 이 페이지를 통해 흐르지 않는다.
 */
@RestController
class PublicPingController {
    @GetMapping("/public/ping")
    fun ping(): Map<String, String> = mapOf("status" to "ok")
}
