package me.ramos.unigate.iam.application.user.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.user.dto.ChangeMyEmailCommand
import me.ramos.unigate.iam.application.user.dto.EmailChangeResult
import me.ramos.unigate.iam.application.user.dto.UpdateKeycloakEmailPayload
import me.ramos.unigate.iam.application.user.port.inbound.ChangeMyEmailInPort
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 이메일 변경 유스케이스 — **두 시스템 쓰기라 또 outbox 다.**
 *
 * ## 가입과 같은 모양, 다른 대가
 * 가입(`RegisterUserService`)과 구조가 같다: IAM DB 를 먼저 커밋하고 Keycloak 반영은 워커가 한다.
 * 다른 점은 **되돌릴 것이 있다**는 것이다. 가입은 실패해도 "만들어지지 않은 사용자" 로 끝나지만,
 * 이메일 변경은 이미 쓰던 계정을 건드리므로 실패 시 **로컬 상태를 되돌려야 한다**(보상).
 *
 * 그 보상을 싸게 만들려고 도메인이 확정 값과 요청 값을 나눠 갖는다
 * ([me.ramos.unigate.iam.domain.user.model.UserProfile] KDoc). 여기서 하는 일은 요청 값을 세우고
 * 지시를 남기는 것뿐이고, 되돌릴 때도 그 요청 값만 지우면 된다.
 *
 * ## 응답은 "접수" 다
 * 커밋 시점에 Keycloak 은 아직 옛 주소를 안다. 그래서 결과에 확정 값과 대기 값을 **둘 다** 담는다 —
 * 클라이언트가 "아직 반영 전" 을 표현할 수 있어야 하기 때문이다. 하나만 주면 FE 는 둘 중 무엇을
 * 보여줘야 할지 알 수 없고, 결국 화면이 거짓말을 하게 된다.
 *
 * ## 로컬 중복 검사는 **1차 방어**일 뿐이다
 * 판정의 SoT 는 Keycloak 이다(가입과 같다 — `IAM_PLATFORM_DECISION.md` §6.3). 여기서 IAM DB 를
 * 보는 것은 흔한 실수를 **빨리** 돌려주기 위해서이지, 이 검사를 통과했다고 성공이 보장되지 않는다.
 * 통과했는데 Keycloak 이 거절하는 경우가 정상 경로에 실재하며, 그때 워커가 보상한다.
 */
@Service
class ChangeMyEmailService(
  private val userProfileRepository: UserProfileRepositoryPort,
  private val outboxPort: OutboxPort,
  private val payloadSerializer: PayloadSerializerPort,
  private val recordAuditEventOutPort: RecordAuditEventOutPort,
  private val clock: Clock,
) : ChangeMyEmailInPort {
  @Transactional
  override fun requestChange(command: ChangeMyEmailCommand): EmailChangeResult {
    val now = Instant.now(clock)
    val profile = userProfileRepository.loadCaller(command.userRef)

    // 1차 방어. 다른 사람이 이미 쓰는 주소면 Keycloak 까지 갈 것 없이 여기서 거절한다.
    // ⚠️ 자기 자신이 걸리는 경우는 도메인의 `EmailUnchanged` 가 잡는다 — 그쪽이 더 정확한 사유다.
    userProfileRepository.findByEmail(command.newEmail)?.let { owner ->
      if (owner.userRef?.value != command.userRef) {
        throw EmailAlreadyRegisteredException(command.newEmail)
      }
    }

    // 상태 검사(ACTIVE 여부·중복 요청·동일 값)는 전부 도메인이 한다. 여기서 또 검사하면
    // 규칙이 두 곳에 생기고 언젠가 어긋난다(`UpdateMyProfileService` 와 같은 판단).
    profile.requestEmailChange(command.newEmail)
    val saved = userProfileRepository.save(profile)

    // 프로필 저장과 지시가 **같은 커밋**이어야 한다. 지시만 유실되면 pendingEmail 이 영원히
    // 남아 다음 변경 요청까지 막는다(도메인이 중복 요청을 거절하므로).
    outboxPort.enqueue(
      OutboxRecord.pending(
        eventType = OutboxEventType.UPDATE_KEYCLOAK_EMAIL,
        payload =
          payloadSerializer.serialize(
            UpdateKeycloakEmailPayload(
              userRef = command.userRef,
              newEmail = command.newEmail,
            ),
          ),
        now = now,
      ),
    )

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.EMAIL_CHANGE_REQUESTED,
        actorRef = command.userRef,
        targetRef = command.userRef,
        // ⚠️ **확정 값**을 남긴다. 이 시점의 사실은 "옛 주소의 사용자가 변경을 요청했다" 이다.
        targetEmail = saved.email,
        detail = mapOf("requestedEmail" to command.newEmail),
      ),
    )

    return EmailChangeResult(
      email = saved.email,
      pendingEmail = saved.pendingEmail,
    )
  }
}
