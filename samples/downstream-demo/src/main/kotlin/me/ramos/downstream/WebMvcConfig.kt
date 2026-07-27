package me.ramos.downstream

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 컨트롤러가 [TenantContext] 를 파라미터로 받을 수 있게 한다.
 *
 * 이게 없으면 Spring 은 `TenantContext` 를 요청 본문으로 바인딩하려 들고, 인가 단계에서 검증한
 * 값 대신 **요청이 준 값**이 들어온다 — 검증을 우회하는 조용한 경로가 생긴다.
 */
@Configuration
class WebMvcConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(TenantContextArgumentResolver())
    }
}
