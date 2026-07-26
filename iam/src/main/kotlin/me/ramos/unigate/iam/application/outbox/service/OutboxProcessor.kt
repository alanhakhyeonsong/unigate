package me.ramos.unigate.iam.application.outbox.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.user.dto.CreateKeycloakUserPayload
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * outbox 레코드 **한 건**을 처리한다 — outbox 패턴의 읽기 측.
 *
 * ## 다중 인스턴스 설계의 핵심이 이 트랜잭션 경계에 있다
 *
 * 클레임(`SELECT ... FOR UPDATE SKIP LOCKED`)부터 결과 반영까지 **한 트랜잭션**으로 묶는다.
 * 외부 호출(Keycloak)이 그 안에 들어가므로 DB 커넥션을 잡고 있게 되는데, 그 대가를 치르고 얻는 것이
 * 훨씬 크다.
 *
 * | | 여기서 택한 방식 (트랜잭션 내 처리) | 대안 (claim → 처리 → 반영) |
 * |---|---|---|
 * | 워커가 죽으면 | 롤백 → **락 해제 → 다른 인스턴스가 즉시 이어받음** | `IN_PROGRESS` 로 멈춤 |
 * | stale lock 회수 | 불필요 | 타임아웃 로직 필요 (그 자체가 버그 원천) |
 * | 커넥션 | 외부 호출 동안 점유 | 빨리 반납 |
 *
 * 다중 인스턴스에서는 "인스턴스가 죽는 것" 이 예외가 아니라 **일상**(롤링 배포, 오토스케일, OOM)이다.
 * 그때 자동으로 다른 인스턴스가 이어받는 성질이 커넥션 점유보다 값지다.
 *
 * ## 왜 건별 트랜잭션인가
 * 여러 건을 한 트랜잭션에 묶으면 (1) 한 건의 실패가 나머지를 롤백시키고 (2) 락 유지 시간이 길어져
 * 다른 인스턴스가 집을 수 있는 일감이 줄어든다. 건별로 끊으면 실패가 격리되고 락도 짧다.
 *
 * ## `REQUIRES_NEW` 인 이유
 * 스케줄러가 여러 건을 순회할 때 **각 건이 독립적으로 커밋/롤백**되어야 한다.
 */
@Service
class OutboxProcessor(
  private val outboxPort: OutboxPort,
  private val identityProviderPort: IdentityProviderPort,
  private val userProfileRepository: UserProfileRepositoryPort,
  private val payloadSerializer: PayloadSerializerPort,
  private val recordAuditEventOutPort: RecordAuditEventOutPort,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 처리 대상 하나를 집어 처리한다.
   *
   * @return 처리한 레코드가 있었으면 `true`. `false` 면 지금은 일감이 없다는 뜻이라 스케줄러가 멈춘다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun processOne(): Boolean {
    val now = Instant.now(clock)
    val record = outboxPort.claimNext(now) ?: return false

    try {
      when (record.eventType) {
        OutboxEventType.CREATE_KEYCLOAK_USER -> createKeycloakUser(record.payload)
      }
      outboxPort.update(record.completed())
      log.info("outbox 처리 완료 id={} type={}", record.id, record.eventType)
    } catch (e: IdentityAlreadyExistsException) {
      // 재시도해도 소용없다. 사용자에게 정정을 요구해야 한다.
      // ⚠️ 예외 메시지를 그대로 저장하지 않는다 — 외부 응답 본문이 섞일 수 있다(CLAUDE.md §8).
      log.warn("outbox 영구 실패(중복 이메일) id={}", record.id)
      markProfileIdentityFailed(record.payload)
      outboxPort.update(record.failedPermanently("identity_already_exists"))
      // ⚠️ 이 감사는 **catch 블록 안**에 있어야 한다. 이 트랜잭션은 롤백되지 않고 커밋된다
      // (예외를 삼켜 실패를 상태로 기록하는 경로이므로). 그래서 감사도 함께 커밋된다.
      recordIdentityFailure(record.payload, "identity_already_exists")
    } catch (e: IdentityProviderUnavailableException) {
      // 재시도 대상. 백오프 후 다시 집힌다. 한도를 넘으면 DEAD 로 간다.
      //
      // ⚠️ 여기서는 **감사를 남기지 않는다.** 재시도 실패는 아직 진행 중인 상태이지 확정된 사건이
      // 아니다. 남기면 Keycloak 이 몇 분 흔들릴 때마다 감사 테이블에 같은 사건이 10건씩 쌓여
      // 정작 확정 사건이 묻힌다. 그 관측은 로그·메트릭의 몫이다.
      log.warn("outbox 재시도 예정 id={} attempts={}", record.id, record.attempts + 1)
      outboxPort.update(record.failedRetryable(now, "identity_provider_unavailable"))
    }
    return true
  }

  private fun createKeycloakUser(payload: String) {
    val command = payloadSerializer.deserialize(payload, CreateKeycloakUserPayload::class.java)

    // ⚠️ 어댑터의 createUser 는 **멱등**이다(조회 → 생성 → 409 면 재조회). outbox 는 최소 1회 실행이라
    // 같은 지시가 두 번 올 수 있고, 그때 중복 생성이 아니라 기존 참조를 받아야 한다.
    val userRef = identityProviderPort.createUser(command.toIdentityCommand())

    val profile =
      userProfileRepository.findByEmail(command.email)
        ?: error("outbox 지시에 대응하는 프로필이 없습니다: ${command.email}")

    // 신원 부여와 ACTIVE 전이는 도메인이 함께 처리한다(따로 두면 불일치 상태를 만들 수 있다).
    profile.completeIdentity(userRef)
    val saved = userProfileRepository.save(profile)

    // 사용자 계정이 실제로 **존재하게 된 순간**이다. IAM 감사에서 가장 중요한 사건 중 하나이며,
    // 이때 비로소 `targetRef`(= Keycloak sub)를 쓸 수 있다.
    //
    // ⚠️ actorRef 는 null 이다 — 이 사건을 일으킨 것은 사람이 아니라 워커다.
    // traceId 는 찍히지만 **원래 가입 요청과 다른 trace** 다(스케줄러가 자기 span 을 만든다).
    // 그래서 가입과 이 사건을 이으려면 trace_id 가 아니라 target_email 로 봐야 한다
    // (`JpaAuditLogAdapter` KDoc 에 실측값과 함께 적어뒀다).
    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.IDENTITY_CREATED,
        targetRef = userRef.value,
        targetEmail = saved.email,
        detail = mapOf("onboardingState" to saved.onboardingState.name),
      ),
    )
  }

  /** 영구 실패 시 프로필도 실패 상태로 옮겨 사용자에게 알릴 수 있게 한다. */
  private fun markProfileIdentityFailed(payload: String) {
    val command = payloadSerializer.deserialize(payload, CreateKeycloakUserPayload::class.java)
    userProfileRepository.findByEmail(command.email)?.let { profile ->
      profile.failIdentity()
      userProfileRepository.save(profile)
    }
  }

  /**
   * 영구 실패를 감사에 남긴다.
   *
   * `targetRef` 가 없다 — 신원 생성에 **실패했으므로** Keycloak sub 자체가 없다. 그래서 이 사건은
   * `target_email` 로만 가리킬 수 있고, 그 컬럼이 nullable 한 `target_ref` 와 함께 존재하는 이유다.
   */
  private fun recordIdentityFailure(
    payload: String,
    reasonCode: String,
  ) {
    val command = payloadSerializer.deserialize(payload, CreateKeycloakUserPayload::class.java)
    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.IDENTITY_CREATION_FAILED,
        targetEmail = command.email,
        reasonCode = reasonCode,
      ),
    )
  }

  private fun CreateKeycloakUserPayload.toIdentityCommand() =
    me.ramos.unigate.iam.application.user.port.outbound.CreateIdentityCommand(
      email = email,
      firstName = firstName,
      lastName = lastName,
    )
}
