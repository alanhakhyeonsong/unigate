// 샘플 다운스트림 BE — 게이트웨이 검증용 계측 도구 (커밋 제외)
//
// 게이트웨이(WebFlux)와 달리 의도적으로 Servlet MVC 를 쓴다.
// 학습 대상을 게이트웨이 하나로 한정하기 위함이며, 다운스트림이 블로킹이어도
// 게이트웨이 입장에서는 그냥 HTTP 백엔드일 뿐이라 무관하다.

plugins {
  kotlin("jvm") version "2.1.21"
  kotlin("plugin.spring") version "2.1.21"
  id("org.springframework.boot") version "3.5.4"
  id("io.spring.dependency-management") version "1.1.7"
}

group = "me.ramos"
version = "0.0.1-SNAPSHOT"

kotlin {
  jvmToolchain(21)
  compilerOptions {
    freeCompilerArgs.add("-Xjsr305=strict")
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  // k8s liveness/readiness probe 용. 샘플이지만 alpha 에 배포되므로 probe 대상이 필요하다.
  // ⚠️ health 엔드포인트는 `ResourceServerConfig` 의 예외 목록에 **한 줄로 명시**해야 열린다
  //    (anyRequest 가 default-deny 이므로). 그 한 줄이 곧 "여기는 인증이 없다" 는 선언이다.
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  // Step 8: Resource Server 승격 — 인입 Bearer JWT 의 서명·iss·aud 를 검증한다.
  // starter-oauth2-resource-server 가 spring-security + nimbus JOSE(JWT 파싱/서명검증)를 끌어온다.
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
  // 모든 모듈이 `app.jar` 로 통일돼 있다 — `docker/server.dockerfile` 과
  // `.dockerignore` 화이트리스트가 그 이름만 인정한다.
  archiveFileName.set("app.jar")
}
