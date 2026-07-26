plugins {
  `kotlin-dsl`
  `java-library`
}

object Versions {
  const val KOTLIN = "2.1.21"
  const val SPRING_BOOT = "3.5.4"
  const val DEPENDENCY_MANAGEMENT = "1.1.7"
  const val KTLINT = "13.0.0"
}

// plugins
dependencies {
  // Kotlin Gradle
  implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:${Versions.KOTLIN}")
  implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:${Versions.KOTLIN}")
  // iam 모듈(JPA) 전용 — Kotlin 클래스는 기본 final 이라 JPA 프록시가 못 만들어진다.
  // 이 플러그인이 @Entity 에 no-arg 생성자와 open 을 넣어준다. gateway(R2DBC)엔 불필요.
  implementation("org.jetbrains.kotlin.plugin.jpa:org.jetbrains.kotlin.plugin.jpa.gradle.plugin:${Versions.KOTLIN}")

  // Spring Boot
  implementation("org.springframework.boot:spring-boot-gradle-plugin:${Versions.SPRING_BOOT}")

  // Dependency Management
  implementation("io.spring.gradle:dependency-management-plugin:${Versions.DEPENDENCY_MANAGEMENT}")

  // ktlint
  implementation("org.jlleitschuh.gradle:ktlint-gradle:${Versions.KTLINT}")
}
