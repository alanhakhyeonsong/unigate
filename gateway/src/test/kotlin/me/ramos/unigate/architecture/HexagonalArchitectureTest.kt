package me.ramos.unigate.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition
import org.junit.jupiter.api.Test

/**
 * 헥사고날 의존성 규칙을 **테스트로 강제**한다 (Phase 5).
 *
 * ## 왜 필요했나
 * `CLAUDE.md` §5 는 `adapter → application → domain` 단방향만 허용한다고 적어뒀지만, Phase 4 까지는
 * **문서로만** 지켜지고 있었다. 어댑터가 4종으로 늘어난 지금, 규칙을 코드로 옮겨 리뷰어의 기억이
 * 아니라 빌드가 막게 한다. 아키텍처 위반은 한 번 섞이면 되돌리는 비용이 급격히 커지기 때문이다.
 *
 * ## 왜 `@ArchTest` 가 아니라 평범한 `@Test` 인가
 * ArchUnit 의 JUnit5 확장(`@AnalyzeClasses` + `@ArchTest`)은 **static 필드**에 규칙을 두는 것을 전제한다.
 * Kotlin 의 `val` 은 private 필드 + getter 로 컴파일돼 그대로는 잡히지 않고, `companion object` +
 * `@JvmField` 같은 우회가 필요해 의도가 흐려진다. 규칙을 `check(classes)` 로 직접 실행하면
 * **어떤 클래스 집합에 무엇을 검사하는지가 코드에 그대로 드러나** Kotlin 에서 더 명확하다.
 *
 * ## 검사 대상
 * 프로덕션 클래스만 본다(`DO_NOT_INCLUDE_TESTS`). 테스트 코드는 어느 레이어든 참조할 수 있어야 한다.
 */
class HexagonalArchitectureTest {
  private val classes: JavaClasses =
    ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(ROOT_PACKAGE)

  @Test
  fun `domain 은 순수 Kotlin 이어야 한다 — Spring 도 다른 레이어도 모른다`() {
    // 가장 안쪽 레이어. 여기가 오염되면 도메인 규칙을 프레임워크 없이 테스트할 수 없게 된다.
    // 허용은 표준 라이브러리(java/kotlin)와 자기 자신뿐이다.
    //
    // ⚠️ `org.jetbrains.annotations..` 를 허용 목록에 넣은 이유 (처음 실행했을 때 실제로 걸린 것):
    // Kotlin 컴파일러가 **자동으로** 파라미터·반환값에 `@NotNull`/`@Nullable` 을 삽입한다.
    // 소스에는 없지만 바이트코드에는 있어서, ArchUnit 은 이를 외부 의존으로 본다.
    //   Method <...AuthenticatedPrincipal.getSubject()> is annotated with <org.jetbrains.annotations.NotNull>
    // 우리가 쓴 의존이 아니라 **컴파일 산물**이므로 허용한다. 런타임 동작이 없는 순수 어노테이션이라
    // 이걸 허용해도 "도메인이 인프라에 묶인다"는 위험은 생기지 않는다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.domain..")
      .should()
      .dependOnClassesThat()
      .resideOutsideOfPackages(
        "$ROOT_PACKAGE.domain..",
        "java..",
        "javax..",
        "kotlin..",
        "org.jetbrains.annotations..",
      ).because(
        "domain 은 순수 Kotlin 이어야 한다 (CLAUDE.md §5). " +
          "Spring 어노테이션이나 DB/HTTP 타입이 들어오면 도메인이 인프라에 묶인다.",
      ).check(classes)
  }

  @Test
  fun `application 은 adapter 를 알아서는 안 된다 — 의존은 포트를 통해 역전된다`() {
    // 이게 뒤집히면 헥사고날이 무너진다. UseCase 가 특정 어댑터를 직접 부르는 순간
    // 그 어댑터 없이는 테스트도 교체도 불가능해진다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.application..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("$ROOT_PACKAGE.adapter..", "$ROOT_PACKAGE.config..")
      .because(
        "application 은 포트 인터페이스로만 바깥과 소통한다. " +
          "어댑터를 직접 참조하면 의존성 역전이 사라진다.",
      ).check(classes)
  }

  @Test
  fun `application 은 Spring 스테레오타입 외의 프레임워크를 쓰지 않는다`() {
    // `@Service` 는 허용한다(현 상태 유지). 그러나 web/data/security/http 같은 **인프라 타입**이
    // 들어오면 UseCase 가 특정 기술에 묶인다. 예컨대 여기서 ServerWebExchange 를 받기 시작하면
    // 그 UseCase 는 WebFlux 없이는 존재할 수 없다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.application..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "org.springframework.web..",
        "org.springframework.http..",
        "org.springframework.data..",
        "org.springframework.security..",
        "org.springframework.r2dbc..",
        "org.springframework.cloud..",
        "io.micrometer..",
        "reactor..",
        "com.fasterxml..",
      ).because(
        "application 은 기술을 몰라야 한다. Spring 은 스테레오타입(@Service 등)까지만 허용한다.",
      ).check(classes)
  }

  @Test
  fun `어댑터끼리 서로 의존하지 않는다`() {
    // gatewayIn 이 r2dbcOut 을 직접 부르는 식이면 어댑터 하나를 갈아끼울 때 다른 어댑터까지 끌려온다.
    // 어댑터 간 소통이 필요하면 반드시 application 의 포트를 경유해야 한다.
    SlicesRuleDefinition
      .slices()
      .matching("$ROOT_PACKAGE.adapter.(*)..")
      .should()
      .notDependOnEachOther()
      .because("어댑터는 서로를 모른 채 독립적으로 교체 가능해야 한다.")
      .check(classes)
  }

  @Test
  fun `domain 은 application 을 알아서는 안 된다`() {
    // 의존 방향은 adapter → application → domain 이다. domain 이 application 을 참조하면 순환이 된다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAPackage("$ROOT_PACKAGE.application..")
      .because("의존은 안쪽으로만 흐른다. domain 이 가장 안쪽이다.")
      .check(classes)
  }

  companion object {
    private const val ROOT_PACKAGE = "me.ramos.unigate"
  }
}
