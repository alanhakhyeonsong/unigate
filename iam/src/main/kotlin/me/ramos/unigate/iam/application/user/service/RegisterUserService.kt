package me.ramos.unigate.iam.application.user.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.user.dto.CreateKeycloakUserPayload
import me.ramos.unigate.iam.application.user.dto.RegisterUserCommand
import me.ramos.unigate.iam.application.user.dto.RegisterUserResult
import me.ramos.unigate.iam.application.user.port.inbound.RegisterUserInPort
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import me.ramos.unigate.iam.domain.user.model.UserProfile
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 가입 유스케이스 — **outbox 패턴의 쓰기 측**.
 *
 * ## 이 클래스가 존재하는 이유는 트랜잭션 경계 하나다
 * `UserProfile` 저장과 `OutboxRecord` 저장이 **반드시 같은 커밋**이어야 한다.
 *
 * - 프로필만 커밋되면 → Keycloak 생성 지시가 유실되어 그 사용자는 영원히 `PENDING_IDENTITY`
 * - outbox 만 커밋되면 → 워커가 존재하지 않는 프로필을 채우려 한다
 *
 * 둘 다 **로컬 DB 쓰기**라 하나의 로컬 트랜잭션으로 원자성이 보장된다. 이것이 "Keycloak 먼저 →
 * IAM DB" 순서를 뒤집은 이유다 — 그 순서에서는 두 시스템에 걸쳐 원자성이 필요해 불가능했다
 * (`IAM_PLATFORM_DECISION.md` §6.3).
 *
 * ## 커밋 직후 응답한다 (202 Accepted 성격)
 * Keycloak 반영은 워커가 나중에 한다. 따라서 **가입 직후에는 로그인할 수 없다.**
 * 그 대가를 알고 택한 것이며, FE 는 `onboardingState` 를 보고 "처리 중" 을 표현해야 한다.
 */
@Service
class RegisterUserService(
  private val userProfileRepository: UserProfileRepositoryPort,
  private val outboxPort: OutboxPort,
  private val payloadSerializer: PayloadSerializerPort,
  private val recordAuditEventOutPort: RecordAuditEventOutPort,
  private val clock: Clock,
) : RegisterUserInPort {
  /**
   * ⚠️ `@Transactional` 이 이 유스케이스의 전부라 해도 과언이 아니다. 떼는 순간 outbox 가 깨진다.
   *
   * 여기서 Keycloak 을 부르지 않는 것도 의도적이다. 외부 호출을 트랜잭션 안에 넣으면
   * 그 응답을 기다리는 동안 DB 커넥션을 잡고 있게 되고, 실패 시 롤백 범위도 모호해진다.
   */
  @Transactional
  override fun register(command: RegisterUserCommand): RegisterUserResult {
    val now = Instant.now(clock)

    // 1차 방어. 최종 중복 판정의 SoT 는 Keycloak 이므로(외부에서 만들어진 사용자는 IAM DB 에 없다)
    // 여기서 통과해도 워커 단계에서 걸릴 수 있다. 그때는 IDENTITY_FAILED 로 간다.
    userProfileRepository.findByEmail(command.email)?.let {
      throw EmailAlreadyRegisteredException(command.email)
    }

    val profile =
      UserProfile.register(
        email = command.email,
        displayName = command.displayName,
        locale = command.locale ?: DEFAULT_LOCALE,
        consent = command.tosVersion?.let { ConsentRecord(tosVersion = it, acceptedAt = now) },
      )
    val saved = userProfileRepository.save(profile)

    outboxPort.enqueue(
      OutboxRecord.pending(
        eventType = OutboxEventType.CREATE_KEYCLOAK_USER,
        payload =
          payloadSerializer.serialize(
            CreateKeycloakUserPayload(
              email = command.email,
              firstName = command.firstName,
              lastName = command.lastName,
            ),
          ),
        now = now,
      ),
    )

    // Phase 8g: 감사도 **같은 트랜잭션**이다. 프로필·outbox 지시와 함께 커밋되거나 함께 롤백된다.
    // 여기에 outbox 를 또 얹지 않는 이유: 감사는 같은 DB 로의 단일 쓰기라 이미 원자적이다
    // (`RecordAuditEventOutPort` KDoc).
    //
    // actorRef 가 null 인 것이 정상이다 — 가입은 **미인증 요청**이라 행위자 토큰이 없다.
    // targetRef 도 아직 없다(신원 생성 전). 그래서 이 구간의 사건은 email 로만 가리킬 수 있고,
    // 그게 `target_email` 컬럼이 존재하는 이유다.
    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.USER_REGISTERED,
        targetEmail = saved.email,
        detail =
          mapOf(
            "onboardingState" to saved.onboardingState.name,
            "locale" to saved.locale,
            // 동의 여부만 남긴다. 동의 자체의 상세는 CONSENT_ACCEPTED 사건이 다룬다.
            "tosVersion" to saved.consent?.tosVersion,
          ),
      ),
    )

    return RegisterUserResult(
      email = saved.email,
      onboardingState = saved.onboardingState.name,
      // 항상 null 이다 — 아직 Keycloak 에 만들어지지 않았다. 그게 정상이다.
      userRef = saved.userRef?.value,
    )
  }

  companion object {
    private const val DEFAULT_LOCALE = "ko-KR"
  }
}

/**
 * 이미 가입된 이메일. **재시도해도 소용없다.**
 *
 * IAM DB 기준의 판정이며, Keycloak 에만 있는 사용자는 여기서 걸리지 않는다(워커가 잡는다).
 */
class EmailAlreadyRegisteredException(
  email: String,
) : RuntimeException("이미 가입된 이메일입니다: $email")
