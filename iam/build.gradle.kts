plugins {
  id("iam")
}

dependencies {
  // ── Resource Server (Phase 8f) ─────────────────────────────────────────
  // GW 가 relay 한 **사용자 JWT** 로 호출자를 식별한다(IAM_PLATFORM_DECISION.md D4 보강 ①).
  //
  // ⚠️ oauth2-**client** 가 아니라 oauth2-**resource-server** 다. IAM 은 로그인을 시키지 않는다 —
  // 그건 게이트웨이(BFF)의 일이고, IAM 은 이미 발급된 토큰을 검증만 한다. client 를 넣으면
  // IAM 에도 로그인 리다이렉트 엔드포인트가 생겨 인증 주체가 둘로 갈라진다.
  //
  // Keycloak Admin 호출 권한은 이 토큰에 **없다.** 그건 service account 토큰이 따로 담당한다
  // (`ServiceAccountTokenProvider`). 두 토큰 컨텍스트를 헷갈리면 설계가 무너진다.
  implementation(libs.spring.boot.starter.oauth2.resource.server)

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
