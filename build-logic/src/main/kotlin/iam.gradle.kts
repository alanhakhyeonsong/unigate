import org.springframework.boot.gradle.tasks.bundling.BootJar

/**
 * unigate IAM 서비스 convention.
 *
 * `gateway.gradle.kts`(WebFlux) 와 **의도적으로 정반대 스택**이다.
 *
 * | | gateway | iam (여기) |
 * |---|---|---|
 * | 웹 | WebFlux + SCG (Netty) | **Servlet MVC (Tomcat)** |
 * | DB | R2DBC (논블로킹) | **JPA / JDBC (블로킹이 정상)** |
 * | 동시성 | Reactive | **Virtual Thread** |
 *
 * ## 왜 여기서는 Virtual Thread 를 켜는가
 * `CLAUDE.md` §1.3 은 "unigate 는 VT 를 쓰지 않는다"고 적어뒀지만 그 근거는 **SCG 가 WebFlux 위에서만
 * 동작한다**는 제약이었다. IAM 은 SCG 가 아니므로 그 제약을 받지 않는다.
 *
 * 오히려 IAM 워크로드가 VT 에 정확히 맞는다:
 * - Keycloak Admin client 는 **블로킹**이다. WebFlux 였다면 이벤트 루프를 막지 않으려고 별도 스레드풀로
 *   퍼내는 곡예가 필요했을 것이다.
 * - 관리 도메인 CRUD 는 JPA 의 관계 매핑·트랜잭션이 R2DBC 보다 적합하다.
 * - 저QPS·비임계 경로라 reactive 의 복잡도를 지불할 이유가 없다.
 *
 * "VT 와 Reactive 를 섞지 말라"는 경고는 **한 애플리케이션 안** 이야기다. 앱을 나눠 쓰는 것은 위반이 아니다.
 * (`docs/IAM_PLATFORM_DECISION.md` §11.1)
 *
 * ## 주의
 * 이 convention 을 `gateway` 에 적용하면 Servlet 스택이 유입돼 **기동 자체가 실패한다.** 반대도 마찬가지다.
 */
plugins {
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  kotlin("plugin.spring")
  // JPA 엔티티는 인자 없는 생성자와 open 클래스를 요구한다. Kotlin 클래스는 기본이 final 이라
  // 이 플러그인 없이는 지연로딩 프록시 생성이 막히고 런타임에야 드러난다.
  kotlin("plugin.jpa")
}

val catalog = extensions.findByType<VersionCatalogsExtension>()?.named("libs")

dependencies {
  catalog?.let {
    add("implementation", it.findLibrary("kotlin-stdlib").get())
    add("implementation", it.findLibrary("kotlin-reflect").get())
    add("implementation", it.findLibrary("jackson-module-kotlin").get())
    add("implementation", it.findLibrary("kotlin-logging").get())

    // Servlet MVC + JPA — gateway 와 정반대 스택 (위 KDoc 참조)
    add("implementation", it.findLibrary("spring-boot-starter-web").get())
    add("implementation", it.findLibrary("spring-boot-starter-data-jpa").get())
    add("implementation", it.findLibrary("spring-boot-starter-validation").get())

    // 헥사고날 레이어/의존성 가드 (모듈 공통 규칙 — CLAUDE.md §5)
    add("testImplementation", it.findLibrary("archunit-junit5").get())

    add("testImplementation", it.findLibrary("spring-boot-starter-test").get())
    // Resource Server 인가 경계 테스트(Phase 8f). Keycloak 없이 인증된 호출자를 흉내 낸다.
    add("testImplementation", it.findLibrary("spring-security-test").get())
    add("testImplementation", it.findLibrary("kotlin-test-junit5").get())
    add("testImplementation", it.findLibrary("kotest-runner-junit5").get())
    add("testImplementation", it.findLibrary("kotest-assertions-core").get())
    add("testImplementation", it.findLibrary("mockk").get())
    add("testImplementation", it.findLibrary("springmockk").get())
    // Keycloak Admin 어댑터 테스트용 — 실제 Keycloak 없이 HTTP 계약(상태코드·헤더)을 검증한다.
    add("testImplementation", it.findLibrary("mockwebserver").get())

    // outbox 의 SKIP LOCKED 동시성은 **실제 PostgreSQL 없이는 검증할 수 없다**(H2 등은 미지원).
    // Testcontainers 를 쓰려 했으나 이 환경의 Docker 29.x 와 Testcontainers 1.21.3(Boot BOM 고정)이
    // 맞지 않아 컨테이너를 띄우지 못했다 — docker-java 가 소켓에서 HTTP 400 을 받는다(curl 은 200).
    // 그래서 docker-compose 로 이미 띄우는 로컬 PostgreSQL 에 직접 붙는다. **검증 목적(실제 PG 에서
    // SKIP LOCKED 확인)은 그대로 달성**되고, Docker 소켓 API 대신 JDBC 만 쓰므로 이 문제를 우회한다.
    // 자세한 경위는 docs/learning/18 참조.
    add("testRuntimeOnly", it.findLibrary("junit-platform-launcher").get())
  }
}

tasks {
  named<BootJar>("bootJar").configure {
    enabled = true
    archiveFileName.set("iam.jar")
  }

  named<Jar>("jar").configure {
    enabled = false
  }
}
