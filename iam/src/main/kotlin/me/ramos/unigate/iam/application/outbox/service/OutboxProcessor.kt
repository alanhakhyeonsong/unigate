package me.ramos.unigate.iam.application.outbox.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.tenant.dto.CreateTenantGroupPayload
import me.ramos.unigate.iam.application.tenant.dto.GroupMembershipPayload
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.application.user.dto.CreateKeycloakUserPayload
import me.ramos.unigate.iam.application.user.dto.UpdateKeycloakEmailPayload
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.enums.TenantStatus
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
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
  private val tenantRepository: TenantRepositoryPort,
  private val circuit: OutboxCircuit,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 처리 대상 하나를 집어 처리한다.
   *
   * @return 처리한 레코드가 있었으면 `true`. `false` 면 **지금 집을 것이 없다**는 뜻이라 스케줄러가
   *   이번 주기를 끝낸다. 일감이 없을 때와 회로가 열렸을 때 둘 다 `false` 다 — 스케줄러 입장에서는
   *   "더 진행하지 말라" 로 같기 때문이다(로그로는 구분한다).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun processOne(): Boolean {
    // ⚠️ **클레임보다 먼저** 묻는다. 집고 나서 실패하면 그게 곧 attempts 소진이고,
    // 외부 장애 때문에 정상 레코드가 DEAD 로 떨어진다([OutboxCircuit] KDoc).
    if (!circuit.canAttempt()) {
      log.warn("outbox 회로 열림 — 이번 주기는 클레임하지 않는다")
      return false
    }

    val now = Instant.now(clock)
    val record = outboxPort.claimNext(now) ?: return false

    when (val outcome = attempt(record)) {
      is ProcessOutcome.Success -> {
        circuit.recordSuccess()
        outboxPort.update(record.completed())
        log.info("outbox 처리 완료 id={} type={}", record.id, record.eventType)
      }

      is ProcessOutcome.Permanent -> {
        // 재시도해도 소용없다. 회로에는 실패로 세지 않는다 — 외부 시스템이 아니라
        // **이 레코드**의 문제이므로, 이것 때문에 다른 레코드까지 멈추면 안 된다.
        log.warn(
          "outbox 영구 실패 id={} reason={} exception={}",
          record.id,
          outcome.reason,
          outcome.exceptionClass,
        )
        // ⚠️ 보상이 터져도 **레코드는 DEAD 로 확정한다.**
        // 여기서 예외가 나가면 트랜잭션이 롤백돼 클레임과 attempts 증가까지 사라지고,
        // 같은 레코드를 영원히 다시 집는다(P9b 가 고친 그 루프). 보상 실패는 로컬 상태를
        // 못 되돌린 것이지, **다시 시도할 이유는 아니다** — 운영자가 DLQ 에서 보게 둔다.
        runCatching { outcome.onPermanentFailure(record.payload) }
          .onFailure { log.error("outbox 보상 실패 — 레코드는 DEAD 로 확정한다 id={}", record.id, it) }
        outboxPort.update(record.failedPermanently(now, outcome.reason, outcome.exceptionClass))
      }

      is ProcessOutcome.Retryable -> {
        // 외부 시스템이 흔들린다. 회로에 실패를 세어, 연속되면 클레임 자체를 멈추게 한다.
        //
        // ⚠️ 여기서는 **감사를 남기지 않는다.** 재시도 실패는 아직 진행 중인 상태이지 확정된 사건이
        // 아니다. 남기면 Keycloak 이 몇 분 흔들릴 때마다 감사 테이블에 같은 사건이 여러 건 쌓여
        // 정작 확정 사건이 묻힌다. 그 관측은 로그·메트릭의 몫이다.
        circuit.recordFailure()
        log.warn("outbox 재시도 예정 id={} attempts={}", record.id, record.attempts + 1)
        outboxPort.update(record.failedRetryable(now, outcome.reason, outcome.exceptionClass))
      }
    }
    return true
  }

  /**
   * 실제 처리를 시도하고 결과를 **분류**한다.
   *
   * ## 이 함수의 존재 이유는 마지막 `catch` 다 (Phase 9b 에서 메운 구멍)
   *
   * 예전에는 예외를 두 종류만 잡고 나머지는 그대로 전파시켰다. 그러면 `@Transactional` 이 트랜잭션을
   * 롤백시키는데, **롤백에는 클레임과 attempts 증가도 포함된다.** 결과는:
   *
   * ```
   * 미분류 예외 → 롤백 → attempts 그대로 PENDING → 5초 뒤 같은 레코드를 다시 집음 → 영원히 반복
   * ```
   *
   * 재시도 상한도 DEAD 도 이 경로에는 닿지 못했다. 실제로 그런 경로가 있었다 —
   * [createKeycloakUser] 의 "대응하는 프로필이 없습니다"(`IllegalStateException`)와 payload
   * 역직렬화 실패다. 둘 다 **재시도로 낫지 않는 버그성 실패**인데 무한히 재시도됐다.
   *
   * 그래서 미분류 예외는 [ProcessOutcome.Permanent] 로 보낸다. "모르는 실패는 일단 재시도" 가
   * 아니라 **"모르는 실패는 사람이 봐야 한다"** 가 맞는 기본값이다 — 모르는 것을 자동으로
   * 반복하는 것은 문제를 감출 뿐이다.
   *
   * ## 남아 있는 한계 (알고 두는 것)
   * DB 예외로 트랜잭션이 이미 abort 된 경우에는 뒤이은 `update` 도 실패해 결국 롤백된다.
   * 즉 그때는 여전히 재시도 루프가 된다. 다만 그건 **정상 동작에 가깝다** — DB 가 아픈 상황에서
   * 레코드를 DEAD 로 확정하는 것이 오히려 위험하다. 위 무한 루프와 달리 원인도 로그에 남는다.
   */
  private fun attempt(record: OutboxRecord): ProcessOutcome =
    try {
      when (record.eventType) {
        OutboxEventType.CREATE_KEYCLOAK_USER -> createKeycloakUser(record.payload)
        OutboxEventType.CREATE_KEYCLOAK_GROUP -> createTenantGroup(record.payload)
        OutboxEventType.ADD_GROUP_MEMBER -> syncGroupMember(record.payload, add = true)
        OutboxEventType.REMOVE_GROUP_MEMBER -> syncGroupMember(record.payload, add = false)
        OutboxEventType.UPDATE_KEYCLOAK_EMAIL -> updateKeycloakEmail(record.payload)
      }
      ProcessOutcome.Success
    } catch (e: IdentityAlreadyExistsException) {
      // 사용자에게 정정을 요구해야 하는 실패.
      ProcessOutcome.Permanent(
        "identity_already_exists",
        e.javaClass.name,
        compensationFor(record.eventType, "identity_already_exists"),
      )
    } catch (e: IdentityProviderUnavailableException) {
      ProcessOutcome.Retryable("identity_provider_unavailable", e.javaClass.name)
    } catch (e: Exception) {
      // 여기가 예전에 비어 있던 자리다. 위 KDoc 참조.
      log.error("outbox 미분류 실패 — DEAD 로 보낸다 id={}", record.id, e)
      ProcessOutcome.Permanent(
        "unclassified_failure",
        e.javaClass.name,
        compensationFor(record.eventType, "unclassified_failure"),
      )
    }

  /**
   * 영구 실패 시 **되돌릴 로컬 상태**를 이벤트 타입별로 고른다.
   *
   * ## 왜 타입별이어야 하나 (겪은 결함)
   * 예전에는 보상이 하나뿐이었고, 그것이 payload 를 **항상 가입 payload 로 역직렬화**했다.
   * 그래서 group 이벤트가 영구 실패하면 보상 안에서 역직렬화가 터지고, 그 예외가
   * `@Transactional(REQUIRES_NEW)` 를 롤백시켜 **클레임과 attempts 증가까지 되돌렸다** —
   * P9b 가 고친 무한 재시도 루프가 다른 경로로 되살아난 것이다.
   *
   * 이벤트 타입이 늘 때마다 이 `when` 이 컴파일 에러로 결정을 요구한다. "무엇을 되돌릴 것인가"
   * 는 지시마다 다르므로, 잊지 못하게 만드는 편이 맞다.
   */
  private fun compensationFor(
    eventType: OutboxEventType,
    reasonCode: String,
  ): (String) -> Unit =
    when (eventType) {
      // 가입: 프로필을 실패 상태로 옮겨 사용자에게 알린다.
      OutboxEventType.CREATE_KEYCLOAK_USER -> { payload ->
        markProfileIdentityFailed(payload)
        // ⚠️ 이 감사는 **예외를 삼킨 경로**에서 남긴다. 이 트랜잭션은 롤백되지 않고 커밋되므로
        // (실패를 상태로 기록하는 경로이므로) 감사도 함께 커밋된다.
        recordIdentityFailure(payload, reasonCode)
      }

      // 이메일 변경: 요청을 버린다. 안 버리면 도메인이 "진행 중" 으로 보고 다음 요청을 막는다.
      OutboxEventType.UPDATE_KEYCLOAK_EMAIL -> { payload ->
        cancelPendingEmailChange(payload, reasonCode)
      }

      // 되돌릴 로컬 상태가 없다. 테넌트는 PENDING 에 머물고 group 멤버십은 반영되지 않은 채
      // 남는데, **그게 사실과 일치하는 상태**다. 레코드는 DEAD 로 남아 DLQ 에서 보인다.
      OutboxEventType.CREATE_KEYCLOAK_GROUP,
      OutboxEventType.ADD_GROUP_MEMBER,
      OutboxEventType.REMOVE_GROUP_MEMBER,
      -> { _ -> }
    }

  /**
   * 처리 결과 분류 — 무엇을 할지는 [processOne] 이 정한다.
   *
   * 처리(try/catch)와 반영(상태 전이·회로·감사)을 나누면, **분류 규칙만 따로 읽고 테스트할 수 있다.**
   * 예전에는 한 `try` 블록 안에 둘이 섞여 있어 "이 예외가 어떤 상태로 가는가" 가 코드를 다 읽어야
   * 보였다.
   */
  private sealed interface ProcessOutcome {
    data object Success : ProcessOutcome

    /** 재시도하면 될 수도 있는 실패. 외부 시스템 사정이므로 회로에도 센다. */
    data class Retryable(
      val reason: String,
      val exceptionClass: String,
    ) : ProcessOutcome

    /**
     * 재시도해도 소용없는 실패.
     *
     * @property onPermanentFailure 확정 시 함께 해야 할 일(프로필 상태 전이·감사). 실행 시점을
     *   [processOne] 이 정하도록 람다로 넘긴다 — 분류 시점에 부수효과를 일으키면 "분류만 하는"
     *   함수가 아니게 된다.
     */
    data class Permanent(
      val reason: String,
      val exceptionClass: String,
      val onPermanentFailure: (payload: String) -> Unit,
    ) : ProcessOutcome
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

  /**
   * 테넌트 group 을 Keycloak 에 만들고, 성공하면 테넌트를 **ACTIVE 로 전이**한다 (Phase 9c-2).
   *
   * ## 상태 전이가 여기 있는 이유
   * `Tenant.create` 는 `PENDING` 으로 시작한다 — group 이 없는 테넌트에 멤버를 넣으면
   * 토큰 claim 에 실릴 근거가 없기 때문이다. 그 프로비저닝이 끝나는 지점이 바로 여기이므로,
   * ACTIVE 전이도 여기서 일어나야 "쓸 수 있다" 는 상태가 사실과 일치한다.
   *
   * ## payload 에 tenantId 만 담고 DB 를 다시 읽는 이유
   * displayName·쿼터를 payload 에 넣으면 그 사이 값이 바뀌었을 때 **낡은 사본**으로 판단하게 된다.
   * 워커는 처리 시점의 최신 상태를 읽는 편이 언제나 정확하다.
   */
  private fun createTenantGroup(payload: String) {
    val command = payloadSerializer.deserialize(payload, CreateTenantGroupPayload::class.java)
    val tenantId = TenantId(command.tenantId)

    // 어댑터의 createTenantGroup 은 **멱등**이다(조회 → 생성 → 409 면 성공 처리).
    identityProviderPort.createTenantGroup(command.tenantId)

    val tenant =
      tenantRepository.findById(tenantId)
        ?: error("outbox 지시에 대응하는 테넌트가 없습니다: ${command.tenantId}")

    // ⚠️ 이미 ACTIVE 면 전이를 건너뛴다. outbox 는 최소 1회 실행이라 같은 지시가 두 번 올 수 있고,
    // 그때 ACTIVE→ACTIVE 를 시도하면 상태기계가 거부해 **성공한 작업이 실패로 기록**된다
    // (markProfileIdentityFailed 에서 겪은 것과 같은 종류의 함정이다).
    if (tenant.status != TenantStatus.ACTIVE) {
      tenant.activate()
      tenantRepository.save(tenant)
    }

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.TENANT_ACTIVATED,
        tenantRef = command.tenantId,
        detail = mapOf("displayName" to tenant.displayName),
      ),
    )
  }

  /**
   * 테넌트 group 의 멤버를 Keycloak 에 반영한다 (Phase 9d).
   *
   * ## 추가와 제거를 한 함수로 둔 이유
   * 둘의 차이가 호출 하나뿐이고, **실패 처리·멱등성 요구가 완전히 같다.** 나누면 같은 주의사항을
   * 두 곳에 적게 되고, 한쪽만 고치는 사고가 난다.
   *
   * ## 여기서 IAM DB 를 바꾸지 않는다
   * 테넌트 생성(`createTenantGroup`)은 성공 후 `ACTIVE` 로 전이시켰지만, 멤버십은 그렇지 않다.
   * 멤버십의 상태(`INVITED`/`ACTIVE`/`REVOKED`)는 **사람의 결정**으로 이미 확정됐고, 워커가 하는
   * 일은 그 결정을 바깥에 **반영**하는 것뿐이다. 여기서 상태를 또 바꾸면 결정 주체가 둘이 된다.
   */
  private fun syncGroupMember(
    payload: String,
    add: Boolean,
  ) {
    val command = payloadSerializer.deserialize(payload, GroupMembershipPayload::class.java)
    if (add) {
      identityProviderPort.addUserToTenantGroup(command.tenantId, command.userRef)
    } else {
      identityProviderPort.removeUserFromTenantGroup(command.tenantId, command.userRef)
    }
  }

  /**
   * Keycloak 에 이메일 변경을 반영하고, 성공하면 **요청 값을 확정 값으로 승격**한다.
   *
   * ## 순서가 중요하다 — Keycloak 먼저
   * 로컬을 먼저 확정하면, Keycloak 반영이 실패했을 때 "IAM 은 새 주소, Keycloak 은 옛 주소" 가
   * 되고 그 어긋남을 되돌릴 근거가 사라진다. 외부 반영이 성공한 뒤에 로컬을 맞추면 최악의
   * 경우가 **"Keycloak 은 반영됐는데 로컬 커밋 실패"** 인데, 그건 재시도가 멱등하게 고친다
   * (`updateEmail` 이 이미 반영된 상태를 성공으로 처리하므로).
   */
  private fun updateKeycloakEmail(payload: String) {
    val command = payloadSerializer.deserialize(payload, UpdateKeycloakEmailPayload::class.java)

    identityProviderPort.updateEmail(command.userRef, command.newEmail)

    val profile =
      userProfileRepository.findByUserRef(UserRef(command.userRef))
        ?: error("outbox 지시에 대응하는 프로필이 없습니다: userRef=${command.userRef}")

    val previousEmail = profile.email
    profile.applyEmailChange()
    val saved = userProfileRepository.save(profile)

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.EMAIL_CHANGED,
        // actorRef 가 null 이다 — 이 사건을 일으킨 것은 워커다. 요청한 사람은
        // EMAIL_CHANGE_REQUESTED 에 남아 있고, 둘은 target_ref 로 이어진다.
        targetRef = command.userRef,
        targetEmail = saved.email,
        detail = mapOf("before" to previousEmail, "after" to saved.email),
      ),
    )
  }

  /**
   * 보상 — 반영 대기 중인 이메일 변경을 취소한다.
   *
   * 이걸 빠뜨리면 `pendingEmail` 이 영원히 남고, 도메인이 "진행 중" 으로 보고 **다음 변경 요청을
   * 계속 거절**한다. 사용자 입장에서는 "한 번 실패한 뒤로 영영 이메일을 못 바꾸는" 상태가 된다.
   */
  private fun cancelPendingEmailChange(
    payload: String,
    reasonCode: String,
  ) {
    val command = payloadSerializer.deserialize(payload, UpdateKeycloakEmailPayload::class.java)
    val profile = userProfileRepository.findByUserRef(UserRef(command.userRef)) ?: return

    profile.cancelEmailChange()
    val saved = userProfileRepository.save(profile)

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.EMAIL_CHANGE_FAILED,
        targetRef = command.userRef,
        // 확정 값(= 그대로 유지된 주소)을 남긴다. 시도했던 값은 detail 에 있다.
        targetEmail = saved.email,
        reasonCode = reasonCode,
        detail = mapOf("requestedEmail" to command.newEmail),
      ),
    )
  }

  /**
   * 영구 실패 시 프로필도 실패 상태로 옮겨 사용자에게 알릴 수 있게 한다.
   *
   * ## 이미 실패 상태면 아무것도 하지 않는다 — **멱등해야 한다**
   * outbox 는 최소 1회 실행이라 같은 지시가 두 번 올 수 있고, 운영자가 DEAD 레코드를 재처리하면
   * 확실히 두 번 온다. 그때 `IDENTITY_FAILED → IDENTITY_FAILED` 전이를 시도하면 도메인이
   * 거부하고([UserProfile] 의 상태기계는 의도적으로 엄격하다), 그 예외가 미분류로 잡혀
   * **실패 처리 도중에 또 실패하는** 꼴이 된다.
   *
   * 도메인의 전이 규칙을 느슨하게 푸는 대신 여기서 거른다 — 상태기계는 엄격한 편이 낫고,
   * "같은 결과라면 다시 하지 않는다" 는 워커의 책임이다.
   */
  private fun markProfileIdentityFailed(payload: String) {
    val command = payloadSerializer.deserialize(payload, CreateKeycloakUserPayload::class.java)
    userProfileRepository.findByEmail(command.email)?.let { profile ->
      if (profile.onboardingState == OnboardingState.IDENTITY_FAILED) {
        log.debug("프로필이 이미 IDENTITY_FAILED — 전이를 건너뛴다 email={}", command.email)
        return@let
      }
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
