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

  // Spring Boot
  implementation("org.springframework.boot:spring-boot-gradle-plugin:${Versions.SPRING_BOOT}")

  // Dependency Management
  implementation("io.spring.gradle:dependency-management-plugin:${Versions.DEPENDENCY_MANAGEMENT}")

  // ktlint
  implementation("org.jlleitschuh.gradle:ktlint-gradle:${Versions.KTLINT}")
}
