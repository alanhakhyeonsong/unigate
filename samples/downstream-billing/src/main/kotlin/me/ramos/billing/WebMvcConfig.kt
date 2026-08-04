package me.ramos.billing

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 컨트롤러가 [TenantScope] 를 파라미터로 받을 수 있게 한다.
 *
 * 이게 없으면 Spring 은 `TenantScope` 를 요청 본문/쿼리로 바인딩하려 들고, 검증한 값 대신
 * **요청이 준 값**이 들어온다 — 검증을 우회하는 조용한 경로가 생긴다.
 * 즉 이 등록을 빠뜨리면 대조군(`ScopedSubscriptionController`)마저 취약해진다.
 */
@Configuration
class WebMvcConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(TenantScopeArgumentResolver())
    }
}
