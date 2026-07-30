package me.ramos.unigate.iam.application.user.port.outbound

import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.model.UserProfile

/**
 * 사용자 프로필 저장소 포트.
 *
 * 도메인 모델([UserProfile])만 주고받는다 — JPA 엔티티는 어댑터 안에 갇힌다.
 * ArchUnit 이 `application` 의 `jakarta.persistence` 의존을 금지하므로 이 경계는 빌드가 강제한다.
 *
 * `email` 을 식별자로 쓴다. 도메인 모델에 DB id 를 두지 않았기 때문인데, 그 편이
 * "도메인은 저장 방식을 모른다" 는 원칙에 맞는다. 대신 어댑터가 email → row 매핑을 책임진다.
 *
 * ## 조회 키가 둘인 이유 (Phase 8e)
 * 가입 흐름은 **email** 로 찾고(토큰이 없으니 그것뿐이다), 프로필 흐름은 **[UserRef]** 로 찾는다.
 * 로그인한 호출자를 식별하는 값은 JWT 의 `sub` 이고 그게 곧 Keycloak 사용자 id, 즉 `UserRef` 다.
 *
 * ⚠️ 프로필 조회를 email 로 하면 안 된다. 토큰의 `email` 클레임은 **Keycloak 에서 바뀔 수 있고**
 * IAM DB 의 email 은 가입 시점 사본이라(도메인 KDoc 참조) 언젠가 어긋난다. 그때 조회가 조용히
 * 0건이 되어 "로그인은 되는데 프로필이 없다" 가 된다. `sub` 는 불변이라 그런 표류가 없다.
 */
interface UserProfileRepositoryPort {
  /**
   * 신규 저장 또는 갱신.
   *
   * @throws ProfileConcurrentlyModifiedException 조회 이후 다른 트랜잭션이 같은 프로필을 바꿨을 때.
   *   **호출자가 반드시 처리해야 한다** — 사용자 요청이면 409, 워커면 재시도다.
   */
  fun save(profile: UserProfile): UserProfile

  fun findByEmail(email: String): UserProfile?

  /**
   * Keycloak 사용자 참조로 프로필을 찾는다 — **로그인한 호출자 본인**을 찾는 유일한 경로다.
   *
   * `PENDING_IDENTITY` 인 프로필은 `userRef` 가 아직 null 이라 여기서 절대 조회되지 않는다.
   * 문제가 아니다 — Keycloak 사용자가 없으면 로그인 자체가 불가능해서 호출자가 될 수 없다.
   *
   * @return 없으면 null. **"토큰은 유효한데 프로필이 없는"** 상태가 실재한다(IAM 을 거치지 않고
   *   Keycloak 에 직접 만들어진 사용자). 그 처리는 유스케이스가 정한다.
   */
  fun findByUserRef(userRef: UserRef): UserProfile?
}

/**
 * 조회 이후 다른 트랜잭션이 같은 프로필을 바꿨다 — 낙관적 락 충돌.
 *
 * ## 왜 Spring 예외를 그대로 쓰지 않는가
 * `OptimisticLockingFailureException` 은 `org.springframework.dao` 소속이다. application 이 그것을
 * 잡으면 유스케이스가 **영속 기술을 알게 된다** — 저장소를 다른 것으로 갈아끼우면 그 catch 가
 * 조용히 무의미해진다(예외 타입이 달라지므로 컴파일은 되고 런타임에만 안 잡힌다).
 *
 * 그래서 [IdentityProviderPort] 가 Keycloak 의 HTTP 상태를 재시도 가능/불가로 번역한 것과
 * 같은 방식으로, 어댑터가 여기서 정의한 타입으로 번역한다.
 *
 * ## **재시도하면 대개 성공한다**
 * 이 실패는 데이터가 잘못된 것이 아니라 **타이밍**이 겹친 것이다. 다시 읽어 다시 적용하면 된다.
 * 워커는 이 예외를 `Retryable` 로 분류해야 하며, `Permanent`(DEAD)로 보내면 정상 지시가
 * 사용자의 프로필 수정과 겹쳤다는 이유만으로 죽는다.
 *
 * ⚠️ 자동 재시도를 **여기서** 하지 않는 이유: 무엇을 다시 할지가 호출자마다 다르다. 워커는 지시를
 * 통째로 다시 실행해야 하고(외부 호출 포함), 사용자 요청은 재시도 대신 최신 상태를 보여주고
 * 다시 결정하게 해야 한다 — 사라진 남의 변경 위에 자기 변경을 덮는 것이 lost update 그 자체다.
 */
class ProfileConcurrentlyModifiedException(
  cause: Throwable? = null,
) : RuntimeException("다른 요청이 먼저 이 프로필을 변경했습니다", cause)
