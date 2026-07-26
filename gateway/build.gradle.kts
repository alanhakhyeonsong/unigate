plugins {
  id("gateway")
}

dependencies {
  // security / BFF + token relay
  implementation(libs.spring.boot.starter.oauth2.client)
  implementation(libs.spring.boot.starter.oauth2.resource.server)

  // session + valkey (reactive)
  implementation(libs.spring.session.data.redis)
  implementation(libs.spring.boot.starter.data.redis.reactive)

  // resilience
  implementation(libs.spring.cloud.starter.circuitbreaker.reactor.resilience4j)

  // persistence: R2DBC(runtime) + Flyway(migration, JDBC)
  implementation(libs.spring.boot.starter.data.r2dbc)
  runtimeOnly(libs.r2dbc.postgresql)
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.postgresql)
  runtimeOnly(libs.postgresql)

  // validation
  implementation(libs.spring.boot.starter.validation)

  // observability
  implementation(libs.spring.boot.starter.actuator)
  runtimeOnly(libs.micrometer.registry.prometheus)
  // 분산 트레이싱. `implementation` 인 이유: 감사로그에 traceId 를 채우려고 `Tracer` 를 직접 주입받는다.
  //
  // 이 한 줄이 Tracer 빈을 만들고, 그게 SCG 의 GatewayTracingConfiguration
  // (@ConditionalOnBean(Tracer))을 깨워 GatewayPropagatingSenderTracingObservationHandler 를 등록한다.
  // → 다운스트림으로 나가는 프록시 요청에 traceparent 가 자동으로 실린다(직접 필터를 짤 필요 없음).
  implementation(libs.micrometer.tracing.bridge.otel)

  // coroutine
  implementation(libs.kotlinx.coroutines.core)
}
