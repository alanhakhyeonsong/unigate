plugins {
  id("iam")
}

dependencies {
  // persistence: JPA(런타임) + Flyway(마이그레이션). 둘 다 JDBC 라 gateway 와 달리 분리가 필요 없다.
  // gateway 는 R2DBC(런타임) + Flyway(JDBC) 를 억지로 병행해야 했지만, 여기선 한 드라이버로 끝난다.
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.postgresql)
  runtimeOnly(libs.postgresql)

  // observability — gateway 와 동일 기준(메트릭 + 트레이싱). traceparent 가 GW→IAM 으로 이어지려면
  // IAM 도 tracing 브리지를 가져야 한다(안 그러면 IAM 구간에서 trace 가 끊긴다).
  implementation(libs.spring.boot.starter.actuator)
  runtimeOnly(libs.micrometer.registry.prometheus)
  implementation(libs.micrometer.tracing.bridge.otel)
}
