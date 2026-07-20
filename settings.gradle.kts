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

// unigate 인증 게이트웨이 애플리케이션 모듈
include("gateway")

// 공용 로직 추출 지점 (필요 시 core/common-* 로 확장)
// include("common-observability")
// project(":common-observability").projectDir = file("core/common-observability")
