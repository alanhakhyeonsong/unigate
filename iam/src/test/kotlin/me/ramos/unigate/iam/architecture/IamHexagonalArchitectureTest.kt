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

  // P8b 시점에는 `application` 패키지가 비어 있어 아래 두 규칙을 넣을 수 없었다. ArchUnit 이
  // "failed to check any classes" 로 막았고, `allowEmptyShould(true)` 로 우회하지 않고 미뤘다
  // (`docs/learning/15-archunit-dependency-guard.md` §5 함정 2).
  // **P8c 에서 `IdentityProviderPort` 가 생겨 이제 검사 대상이 있다.**

  @Test
  fun `application 은 adapter 를 알아서는 안 된다`() {
    // 포트는 application 이 소유하고 어댑터가 구현한다. 이 방향이 뒤집히면 의존성 역전이 사라진다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.application..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("$ROOT_PACKAGE.adapter..", "$ROOT_PACKAGE.config..")
      .because("application 은 포트 인터페이스로만 바깥과 소통한다.")
      .check(classes)
  }

  @Test
  fun `application 은 웹·영속성 기술을 직접 쓰지 않는다`() {
    // 게이트웨이와 금지 목록이 다르다 — 여기는 Servlet/JPA 계열을 막는다.
    //
    // 특히 중요한 것: `IdentityProviderPort` 가 `RestClient` 나 Keycloak 응답 타입을 시그니처에
    // 노출하는 순간 Admin API 봉인이 깨진다. 그 회귀를 이 규칙이 막는다.
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
        "jakarta.persistence..",
        "jakarta.servlet..",
        "org.hibernate..",
        // 직렬화 라이브러리도 막는다. outbox payload 를 JSON 으로 만드는 것은 **저장 형식**의 문제라
        // 어댑터 관심사다 — UseCase 가 ObjectMapper 를 직접 쓰면 그 경계가 무너진다.
        // (그래서 PayloadSerializerPort 로 뽑았다.)
        "com.fasterxml..",
      ).because("application 은 기술을 몰라야 한다. Spring 은 스테레오타입(@Service 등)까지만 허용한다.")
      .check(classes)
  }

  @Test
  fun `application 은 트랜잭션 어노테이션만 Spring 에서 가져온다`() {
    // `@Transactional` 은 예외적으로 허용한다 — 트랜잭션 경계는 **유스케이스의 본질적 책임**이고
    // (outbox 는 특히 그렇다: 프로필과 지시가 같은 커밋이어야 한다), 이를 어댑터로 밀어내면
    // 경계가 코드에서 보이지 않게 된다.
    //
    // 다만 그 예외가 다른 Spring 기능으로 번지지 않도록 위 규칙이 web/data/security 를 막는다.
    // 이 테스트는 그 의도를 문서화하는 역할이다.
    noClasses()
      .that()
      .resideInAPackage("$ROOT_PACKAGE.application..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("org.springframework.context..", "org.springframework.beans..")
      .because("application 은 Spring 컨테이너 API 를 직접 다루지 않는다.")
      .check(classes)
  }

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
