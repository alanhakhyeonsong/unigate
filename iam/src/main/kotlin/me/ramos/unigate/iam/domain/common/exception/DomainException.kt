package me.ramos.unigate.iam.domain.common.exception

/**
 * 모든 도메인 예외의 베이스.
 *
 * **순수 예외다.** HTTP 상태코드·resultCode·messageCode를 알지 않는다. 도메인은 "무엇이 잘못됐는지"만
 * 말하고, 그것을 어떤 응답으로 번역할지는 바깥(application·adapter)이 정한다.
 *
 * 이 경계를 지키지 않으면 도메인 규칙을 검증하는 단위 테스트가 HTTP를 알아야 하는 상황이 온다.
 * `HexagonalArchitectureTest`가 `domain` 패키지의 외부 의존을 0으로 강제하므로 위반 시 빌드가 깨진다.
 */
abstract class DomainException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
