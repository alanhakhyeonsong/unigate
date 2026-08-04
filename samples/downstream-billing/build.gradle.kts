// 샘플 다운스트림 BE **2대째** — "다운스트림이 하나일 때는 구조적으로 안 보이는 것"을 드러내는 도구.
//
// downstream-demo 와 거의 같은 스택이지만 **일부러 대칭이 아니다**:
//   · 자기 audience client 를 따로 갖는다 (unigate-billing-demo)
//   · 테넌트 판정을 **토큰의 소속 목록**으로 한다 (⚠️ 의도적 취약 — samples/README.md §3)
//
// 둘을 나란히 띄워야 공유 aud 재생·claim 누출·교차 테넌트 구멍이 재현된다.

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
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
  archiveFileName.set("app.jar")
}
