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
    // 로컬 실행은 :module:integrationTest 태스크(includeTags) 사용.
    excludeTags("testcontainers")
  }
}
