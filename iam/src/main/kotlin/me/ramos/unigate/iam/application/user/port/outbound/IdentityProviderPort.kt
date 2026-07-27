package me.ramos.unigate.iam.application.user.port.outbound

import me.ramos.unigate.iam.domain.shared.vo.UserRef

/**
 * 외부 신원 공급자(Keycloak)에 대한 아웃바운드 포트 — **Admin API 봉인 경계**.
 *
 * ## 이 포트가 지키는 것
 * IAM 은 신원을 재구현하지 않고 Keycloak 에 위임한다(`IAM_PLATFORM_DECISION.md` §6.1). 그 위임을
 * **한 지점으로 모아** Keycloak 고유 개념(UserRepresentation, realm, credentials 배열 …)이 application·
 * domain 으로 새지 않게 한다. 여기 시그니처에 등장하는 타입은 전부 우리 것이다.
 *
 * 게이트웨이의 `TokenVerifierPort` 가 OIDC 표준에만 의존하는 것과 같은 봉인 원리이며, 이쪽은
 * **Admin API 전용**이다. 게이트웨이는 Admin API 를 절대 부르지 않는다(D7).
 *
 * ## 왜 `suspend` 가 아닌가
 * `iam` 은 Servlet MVC + Virtual Thread 다. 블로킹 호출이 정상이고, VT 가 블로킹 시 캐리어 스레드를
 * 반납하므로 코루틴이 필요 없다. 게이트웨이의 포트가 전부 `suspend` 인 것과 대비된다
 * (`CLAUDE.md` §5.1 — 모듈마다 다르다).
 *
 * ## 지금 만들지 않은 것
 * `assignGroup` / `assignRealmRole` 은 **소비자가 생기는 P9(테넌트)에서 추가**한다. 지금 만들면
 * 호출자 없는 메서드가 되고, 그건 Phase 5 에서 `TokenIssuerPort` 를 미룬 것과 같은 이유로 피한다.
 */
interface IdentityProviderPort {
  /**
   * 신원을 생성하고 그 참조를 돌려준다.
   *
   * ## 멱등해야 한다
   * outbox 워커가 **최소 1회 실행**을 보장하므로 같은 요청이 두 번 올 수 있다(커밋 후 워커가 죽고
   * 재시작한 경우 등). 구현체는 이미 존재하는 신원을 만나면 **그 참조를 반환**해야 하며,
   * [IdentityAlreadyExistsException] 은 "우리가 만들지 않은 다른 사용자와 충돌"할 때만 던진다.
   *
   * @throws IdentityAlreadyExistsException 같은 이메일의 **다른** 사용자가 이미 있다(정정 필요)
   * @throws IdentityProviderUnavailableException 통신·인증 실패 등 **재시도하면 될 수도 있는** 실패
   */
  fun createUser(command: CreateIdentityCommand): UserRef

  /** 이메일로 신원을 찾는다. 없으면 `null`. 멱등 생성과 조회 두 곳에서 쓴다. */
  fun findByEmail(email: String): UserRef?

  /**
   * 테넌트 group(`/tenants/{tenantId}`)을 만든다 (Phase 9c-2).
   *
   * ## 왜 group 인가
   * 단일 realm 에서 테넌트를 표현하는 방법으로 group 을 골랐다(`IAM_PLATFORM_DECISION.md` §7.1).
   * realm role 은 테넌트마다 하나씩 늘어 폭증하고 계층을 못 만든다. user attribute 는 단일값이라
   * **한 사용자가 여러 테넌트에 속하는** 우리 도메인과 애초에 맞지 않는다.
   *
   * ## 멱등해야 한다
   * [createUser] 와 같은 이유다 — outbox 는 최소 1회 실행이라 같은 지시가 두 번 올 수 있다.
   * 이미 있는 group 을 만나면 **성공으로 처리**해야 하며, 예외를 던지면 정상 재시도가
   * 영구 실패로 둔갑한다.
   *
   * ## 부모 group 도 함께 보장한다
   * `/tenants/{id}` 는 `tenants` 라는 부모 group 아래의 자식이다. 구현체는 부모가 없으면
   * 만들어야 한다 — realm 초기 상태에 그것이 있으리라 가정하면, realm 을 새로 만든 환경에서
   * **첫 테넌트 생성이 실패**한다.
   *
   * @throws IdentityProviderUnavailableException 통신·인증 실패 등 **재시도하면 될 수도 있는** 실패
   */
  fun createTenantGroup(tenantId: String)
}

/**
 * 신원 생성 요청.
 *
 * 비밀번호를 담지 않는다 — 초기 비밀번호 설정은 Keycloak 의 이메일 인증·비밀번호 설정 플로우에
 * 맡기는 편이 안전하고, 평문 비밀번호가 IAM DB·로그·outbox 레코드에 남는 경로를 아예 만들지 않기
 * 위해서다(`CLAUDE.md` §8).
 */
data class CreateIdentityCommand(
  val email: String,
  val firstName: String,
  val lastName: String,
) {
  init {
    require(email.isNotBlank()) { "이메일은 비어 있을 수 없습니다" }
  }
}

/**
 * 같은 이메일의 다른 신원이 이미 존재한다.
 *
 * **재시도해도 소용없다.** outbox 워커는 이 예외를 만나면 `UserProfile` 을 `IDENTITY_FAILED` 로
 * 전이시키고 사용자에게 정정을 요구해야 한다.
 */
class IdentityAlreadyExistsException(
  email: String,
) : RuntimeException("이미 사용 중인 이메일입니다: $email")

/**
 * 신원 공급자와 통신하지 못했거나 일시적으로 실패했다.
 *
 * **재시도 대상이다.** outbox 레코드를 남겨두고 다음 폴링에서 다시 시도한다.
 * 원인 메시지에 토큰·secret 이 섞이지 않도록 구현체가 주의해야 한다.
 */
class IdentityProviderUnavailableException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
