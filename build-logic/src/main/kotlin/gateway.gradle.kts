import org.springframework.boot.gradle.tasks.bundling.BootJar

/**
 * unigate 게이트웨이 애플리케이션 convention.
 *
 * MVC 전제의 일반 app convention 대신 신설한다.
 * Spring Cloud Gateway 는 Netty/WebFlux 기반이라 servlet 스택(spring-boot-starter-web)을
 * 클래스패스에 섞으면 자동설정이 충돌한다. 따라서 reactive 스택만 구성한다.
 */
plugins {
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  kotlin("plugin.spring")
}

val catalog = extensions.findByType<VersionCatalogsExtension>()?.named("libs")

dependencyManagement {
  imports {
    catalog?.let {
      mavenBom("org.springframework.cloud:spring-cloud-dependencies:${it.findVersion("spring-cloud").get()}")
    }
  }
}

dependencies {
  catalog?.let {
    add("implementation", it.findLibrary("kotlin-stdlib").get())
    add("implementation", it.findLibrary("kotlin-reflect").get())
    add("implementation", it.findLibrary("jackson-module-kotlin").get())
    add("implementation", it.findLibrary("kotlin-logging").get())

    // reactive gateway core
    add("implementation", it.findLibrary("spring-cloud-starter-gateway-server-webflux").get())
    add("implementation", it.findLibrary("kotlinx-coroutines-reactor").get())

    // 헥사고날 레이어/의존성 가드
    add("testImplementation", it.findLibrary("archunit-junit5").get())

    // test 공통
    add("testImplementation", it.findLibrary("spring-boot-starter-test").get())
    add("testImplementation", it.findLibrary("kotlin-test-junit5").get())
    add("testImplementation", it.findLibrary("kotest-runner-junit5").get())
    add("testImplementation", it.findLibrary("kotest-assertions-core").get())
    add("testImplementation", it.findLibrary("mockk").get())
    add("testImplementation", it.findLibrary("springmockk").get())
    add("testRuntimeOnly", it.findLibrary("junit-platform-launcher").get())
  }
}

tasks {
  named<BootJar>("bootJar").configure {
    enabled = true
    archiveFileName.set("app.jar")
  }

  named<Jar>("jar").configure {
    enabled = false
  }
}
