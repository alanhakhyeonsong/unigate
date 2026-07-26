rootProject.name = "unigate"

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

// unigate 인증 게이트웨이 애플리케이션 모듈 (WebFlux + SCG)
include("gateway")

// IAM 서비스 모듈 (Servlet MVC + JPA + Virtual Thread) — Phase 8.
// gateway 와 스택이 정반대다. 두 모듈은 클래스패스를 공유하지 않으므로 충돌하지 않는다.
// (한 앱 안에서 섞으면 SCG 자동설정이 깨진다 — CLAUDE.md §5.1)
include("iam")

// 공용 로직 추출 지점 (필요 시 core/common-* 로 확장)
// include("common-observability")
// project(":common-observability").projectDir = file("core/common-observability")
