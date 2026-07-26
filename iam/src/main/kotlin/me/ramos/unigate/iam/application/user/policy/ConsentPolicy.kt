package me.ramos.unigate.iam.application.user.policy

/**
 * 현재 유효한 약관 버전 — **서버가 아는 유일한 진실**.
 *
 * ## 왜 순수 Kotlin 인가
 * `@Value`/`@ConfigurationProperties` 를 쓰면 `application` 이 Spring 을 알게 되고, ArchUnit 이
 * `org.springframework.beans..` 를 막는다. 그래서 [Clock] 과 같은 방식으로 처리한다 —
 * 값 자체는 순수 타입으로 두고, 설정에서 읽어 빈으로 만드는 일은 `config` 가 한다
 * (`IamConfig.consentPolicy`).
 *
 * 부수 효과로 테스트가 쉬워진다. 약관 버전을 바꿔가며 검증하는 데 Spring 컨텍스트가 필요 없다.
 *
 * ## 왜 "정책" 인가
 * 이 값은 인프라 설정이 아니라 **비즈니스 규칙**이다. 약관을 개정하면 기존 동의가 일제히 무효가 되고
 * 사용자에게 재동의를 받아야 한다. 그 파급을 한 값이 결정하므로, 배포 설정 어딘가에 흩어두지 않고
 * 이름 붙인 타입으로 드러낸다.
 *
 * ## ⚠️ `@JvmInline value class` 로 만들면 안 된다 (실제로 겪었다)
 * 값이 하나뿐이라 인라인 클래스가 자연스러워 보이지만, **Spring 이 주입하지 못한다.** 인라인
 * 클래스는 생성자 시그니처에서 원시 타입으로 지워지고 Kotlin 이 `DefaultConstructorMarker` 를
 * 덧붙이는데, Spring 은 그것을 주입 대상으로 착각한다:
 *
 * ```
 * No qualifying bean of type 'kotlin.jvm.internal.DefaultConstructorMarker' available
 * ```
 *
 * 더 고약한 건 **언제 드러나느냐**다. 단위 테스트는 직접 생성하고 슬라이스 테스트는 InPort 를
 * 모킹하므로 둘 다 통과한다. 풀 컨텍스트가 뜨는 통합 테스트에서야 기동이 깨진다.
 *
 * 도메인 VO([me.ramos.unigate.iam.domain.shared.vo.UserRef] 등)에는 인라인 클래스가 여전히 옳다 —
 * 그것들은 **주입 대상이 아니라** 값으로만 오간다. 구분 기준은 "빈으로 등록되는가" 다.
 */
data class ConsentPolicy(
  val currentTosVersion: String,
) {
  init {
    require(currentTosVersion.isNotBlank()) { "현재 약관 버전은 비어 있을 수 없습니다" }
  }
}
