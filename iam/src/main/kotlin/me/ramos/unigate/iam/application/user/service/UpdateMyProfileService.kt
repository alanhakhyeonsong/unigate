package me.ramos.unigate.iam.application.user.service

import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.dto.UpdateMyProfileCommand
import me.ramos.unigate.iam.application.user.policy.ConsentPolicy
import me.ramos.unigate.iam.application.user.port.inbound.UpdateMyProfileInPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프로필 수정 유스케이스 — **표시 이름·locale 만**.
 *
 * ## 왜 email 은 못 바꾸나
 * email 은 IAM 이 소유한 값이 아니다. SoT 는 Keycloak 이고 IAM 의 것은 가입 시점 사본이다
 * ([me.ramos.unigate.iam.domain.user.model.UserProfile] KDoc). 여기서 IAM DB 만 고치면
 * **Keycloak 과 어긋나서 로그인 이메일과 표시 이메일이 달라진다.**
 *
 * 제대로 하려면 Keycloak Admin 반영이 필요하고, 그건 두 시스템 쓰기라 **또 outbox** 를 타야 한다
 * (가입과 같은 이유 — `IAM_PLATFORM_DECISION.md` §6.3). 별도 유스케이스로 남긴다.
 *
 * ## 검증은 도메인이 한다
 * blank 검사는 `changeDisplayName`/`changeLocale` 안에 있다. 여기서 한 번 더 하면 규칙이 두 곳에
 * 생기고 언젠가 어긋난다. 유스케이스는 **오케스트레이션만** 한다.
 */
@Service
class UpdateMyProfileService(
  private val userProfileRepository: UserProfileRepositoryPort,
  private val consentPolicy: ConsentPolicy,
) : UpdateMyProfileInPort {
  @Transactional
  override fun update(command: UpdateMyProfileCommand): MyProfileResult {
    val profile = userProfileRepository.loadCaller(command.userRef)

    // null = 변경하지 않음. `?.let` 이 그 시맨틱을 그대로 표현한다.
    command.displayName?.let(profile::changeDisplayName)
    command.locale?.let(profile::changeLocale)

    // ⚠️ JPA 라면 더티체킹으로 자동 반영되지만, 여기서는 **명시적으로 저장한다.**
    // 도메인 모델은 영속 객체가 아니라 어댑터가 엔티티로 매핑해 넣는 별개 인스턴스다
    // (ArchUnit 이 도메인의 @Entity 를 막은 결과). 저장을 빠뜨리면 **아무 에러 없이 조용히
    // 변경이 사라진다** — 응답에는 바뀐 값이 담겨 성공처럼 보이므로 특히 고약하다.
    return userProfileRepository.save(profile).toMyProfileResult(consentPolicy)
  }
}
