import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Kotlin 은 기본적으로 제네릭 타입인자의 type-use 애노테이션(`List<@NotBlank String>`,
        // `List<@Valid X>`)을 bytecode 로 emit 하지 않아 Bean Validation 의 container element 제약이
        // 동작하지 않는다. 이 플래그로 JVM type 애노테이션을 emit 해 표준 element 검증이 동작하게 한다.
        freeCompilerArgs.add("-Xemit-jvm-type-annotations")
    }
}

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs("-Xshare:off", "-XX:+EnableDynamicAgentLoading")

  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = TestExceptionFormat.FULL
    showStandardStreams = true
    showCauses = true
    showStackTraces = true
  }
}

tasks.named<Test>("test") {
  useJUnitPlatform {
    // Testcontainers 기반 통합 테스트는 CI 러너에 Docker 가 없어 실행 불가 → 정적 배제.
    // 로컬 실행은 :module:integrationTest 태스크(아래) 사용.
    excludeTags("testcontainers")
  }
}

/**
 * Testcontainers 통합 테스트 전용 태스크 — **로컬에서만** 돌린다(Docker 필요).
 *
 * `test` 가 `excludeTags("testcontainers")` 로 걸러낸 것들을 여기서 반대로 **포함만** 한다.
 * 그래서 `./gradlew build` 는 Docker 없이 통과하고, 실제 DB 가 필요한 검증은 명시적으로 실행한다:
 *
 * ```bash
 * ./gradlew :iam:integrationTest
 * ```
 *
 * `outputs.upToDateWhen { false }` — 소스가 그대로여도 매번 실행한다. 컨테이너 기동·동시성 검증은
 * 환경에 따라 결과가 달라질 수 있어 "이전에 통과했으니 건너뛴다" 가 위험하다.
 */
val integrationTest = tasks.register<Test>("integrationTest") {
  description = "Testcontainers 기반 통합 테스트 (로컬 전용, Docker 필요)"
  group = "verification"

  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath

  useJUnitPlatform {
    includeTags("testcontainers")
  }
  outputs.upToDateWhen { false }
}
