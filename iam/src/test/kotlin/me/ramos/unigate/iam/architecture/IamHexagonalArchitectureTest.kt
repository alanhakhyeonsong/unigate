package me.ramos.unigate.iam.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition
import org.junit.jupiter.api.Test

/**
 * `iam` 모듈의 헥사고날 의존성 가드 (Phase 8).
 *
 * ## 스택은 달라도 의존성 규칙은 같다
 * `iam` 은 Servlet MVC + JPA + Virtual Thread 로 게이트웨이(WebFlux)와 **정반대 스택**이지만,
 * `adapter → application → domain` 단방향 규칙은 **모듈 공통**이다(`CLAUDE.md` §5).
 * 스택이 다르다고 아키텍처 원칙까지 달라지지는 않는다.
 *
 * ## 게이트웨이 규칙과 다른 점 하나: JPA 금지
 * IAM 에서 가장 현실적인 위험은 **`@Entity` 를 도메인 모델에 직접 붙이는 것**이다. JPA 를 쓰는 순간
 * "엔티티 = 도메인 모델" 유혹이 강해지는데, 그렇게 하면 도메인이 영속성 구조(연관관계 매핑, 지연로딩,
 * 기본 생성자 요구)에 끌려간다. 게이트웨이는 R2DBC 라 이 유혹이 약했지만 여기서는 명시적으로 막는다.
 *
 * `jakarta.persistence..` 를 domain 에서 금지하는 규칙이 그 방어선이다.
 */
class IamHexagonalArchitectureTest {
  private val classes: JavaClasses =
    ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages(ROOT_PACKAGE)

  @Test
  fun `domain 은 순수 Kotlin 이어야 한다`() {
    // 허용은 표준 라이브러리와 자기 자신뿐.
    // org.jetbrains.annotations 는 Kotlin 컴파일러가 자동 삽입하는 것이라 허용한다
    // (소스에 없는 컴파일 산물 — docs/learning/15 §5 함정 1).
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
      ).because("domain 은 순수 Kotlin 이어야 한다 (CLAUDE.md §5).")
      .check(classes)
  }

  @Test
  fun `domain 에 JPA 어노테이션을 붙이지 않는다`() {
    // 위 규칙에 이미 포함되지만 **의도를 드러내려고** 따로 둔다.
    // 실패 메시지가 "외부 패키지 의존" 이 아니라 "JPA 를 도메인에 붙였다" 로 읽혀야
    // 다음 사람이 무엇을 잘못했는지 바로 안다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
      .because(
        "도메인 모델과 JPA 엔티티는 분리한다. @Entity 를 도메인에 붙이면 " +
          "기본 생성자·open 클래스·연관관계 매핑 같은 영속성 사정이 도메인 설계를 지배한다.",
      ).check(classes)
  }

  // ⚠️ `application` 레이어 규칙은 **일부러 아직 넣지 않았다.**
  //
  // P8b(도메인 모델)까지만 진행한 시점이라 `me.ramos.unigate.iam.application` 패키지에 클래스가 0개다.
  // 규칙을 넣었더니 ArchUnit 이 이렇게 막았다:
  //
  //   Rule '...' failed to check any classes. This means either that no classes have been passed
  //   to the rule at all, or that no classes passed to the rule matched the `that()` clause.
  //
  // 여기서 `allowEmptyShould(true)` 로 통과시키는 선택지가 있지만 **그렇게 하지 않는다.** 그건
  // 아무것도 검사하지 않는 규칙을 "우리는 안전하다"는 증거로 위장하는 것이다
  // (`docs/learning/15-archunit-dependency-guard.md` §5 함정 2).
  //
  // → application 이 실제로 생기는 **P8d(가입 유스케이스)** 에서 다음 두 규칙을 추가한다:
  //    1. application → adapter/config 참조 금지
  //    2. application 에서 web/http/data/security/jakarta.persistence/servlet/hibernate 금지
  //       (게이트웨이와 금지 목록이 다르다 — 여기는 Servlet/JPA 계열을 막는다)

  @Test
  fun `domain 은 application 을 알아서는 안 된다`() {
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.domain..")
      .should()
      .dependOnClassesThat()
      .resideInAPackage("$ROOT_PACKAGE.application..")
      .because("의존은 안쪽으로만 흐른다. domain 이 가장 안쪽이다.")
      .check(classes)
  }

  @Test
  fun `어댑터끼리 서로 의존하지 않는다`() {
    SlicesRuleDefinition
      .slices()
      .matching("$ROOT_PACKAGE.adapter.(*)..")
      .should()
      .notDependOnEachOther()
      .because("어댑터는 서로를 모른 채 독립적으로 교체 가능해야 한다.")
      .check(classes)
  }

  companion object {
    private const val ROOT_PACKAGE = "me.ramos.unigate.iam"
  }
}
