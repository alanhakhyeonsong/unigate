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

  // coroutine
  implementation(libs.kotlinx.coroutines.core)
}
