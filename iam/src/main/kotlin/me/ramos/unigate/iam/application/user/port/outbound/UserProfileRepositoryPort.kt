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
  /** 신규 저장 또는 갱신(email 기준). */
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
