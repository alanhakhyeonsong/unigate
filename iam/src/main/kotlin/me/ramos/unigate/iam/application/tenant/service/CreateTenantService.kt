package me.ramos.unigate.iam.application.tenant.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.tenant.dto.CreateTenantGroupPayload
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import me.ramos.unigate.iam.domain.membership.model.Membership
import me.ramos.unigate.iam.domain.membership.vo.TenantRole
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.model.Tenant
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import me.ramos.unigate.iam.domain.tenant.vo.TenantQuota
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 테넌트 온보딩 (Phase 9c-2) — `Tenant`·`Membership` 도메인의 **첫 소비자**.
 *
 * ## 이 유스케이스가 outbox 의 두 번째 사용처다
 * 가입(UC-1)과 같은 모양의 문제다 — IAM DB 쓰기와 Keycloak 쓰기가 한 트랜잭션에 묶이지 않는다.
 * 같은 해법을 그대로 쓴다:
 *
 * ```
 * [ 한 트랜잭션 ] Tenant(PENDING) + Membership(생성자=tenant-admin) + Outbox 지시 + 감사
 *        ↓ 커밋 → 201 응답
 * [ 워커 ]        Keycloak group 생성 → Tenant ACTIVE 전이 + 감사
 * ```
 *
 * ## 왜 `PENDING` 으로 시작하나
 * group 이 없는 테넌트에 멤버를 넣으면 **토큰 claim 에 실릴 근거가 없다**(P9e 가 group 을 claim 으로
 * 투영한다). 그래서 도메인이 `create` 에서 PENDING 을 강제하고, 프로비저닝이 끝나는 지점
 * (워커)에서만 ACTIVE 가 된다.
 *
 * 대가는 **생성 직후 잠깐 쓸 수 없다**는 것이다. 가입 직후 로그인이 안 되는 것과 같은 성질의
 * 대가이며, 응답이 201 이지만 의미상 202(Accepted)에 가깝다.
 *
 * ## 생성자를 곧바로 활성 멤버로 넣는 이유
 * 초대할 사람이 없다. 테넌트를 막 만든 사람이 첫 관리자여야 하고, 그렇지 않으면 **아무도 손댈 수
 * 없는 테넌트**가 만들어진다(`IAM_PLATFORM_DECISION.md` §7.5). `Membership.joinDirectly` 가
 * 정확히 이 경우를 위한 팩토리다.
 */
@Service
class CreateTenantService(
  private val tenantRepository: TenantRepositoryPort,
  private val outboxPort: OutboxPort,
  private val payloadSerializer: PayloadSerializerPort,
  private val recordAuditEventOutPort: RecordAuditEventOutPort,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 테넌트를 만들고 생성자를 첫 tenant-admin 으로 등록한다.
   *
   * @param creatorRef 생성자의 `sub`. 첫 관리자가 되며 감사의 행위자로 남는다.
   * @throws TenantAlreadyExistsException 같은 id 의 테넌트가 이미 있다
   */
  @Transactional
  fun create(command: CreateTenantCommand): CreateTenantResult {
    val tenantId = TenantId(command.tenantId)

    // ⚠️ 사전 검사는 **경합에 안전하지 않다.** 동시에 같은 id 로 두 요청이 오면 둘 다 통과할 수
    // 있고, 그때는 PK 제약이 최종 방어선이 된다(어댑터에서 DataIntegrityViolation 으로 올라온다).
    // 그래도 여기서 검사하는 이유는 **흔한 경우에 명확한 에러**를 주기 위해서다.
    if (tenantRepository.existsById(tenantId)) {
      throw TenantAlreadyExistsException(command.tenantId)
    }

    val now = Instant.now(clock)
    val tenant =
      Tenant.create(
        id = tenantId,
        displayName = command.displayName,
        quota =
          command.maxUsers
            ?.let { TenantQuota(maxUsers = it, featureFlags = emptySet()) }
            ?: TenantQuota.defaultQuota(),
      )
    tenantRepository.save(tenant)

    val membership =
      Membership.joinDirectly(
        userRef = UserRef(command.creatorRef),
        tenantId = tenantId,
        role = TenantRole.tenantAdmin(),
        at = now,
      )
    tenantRepository.saveMembership(membership)

    // 여기까지가 로컬 트랜잭션이다. Keycloak 반영은 지시로 남기고 워커에게 넘긴다 —
    // 이 커밋 안에서 외부를 부르면 "DB 는 됐는데 Keycloak 은 안 된" 상태를 만들 수 있다.
    outboxPort.enqueue(
      OutboxRecord.pending(
        eventType = OutboxEventType.CREATE_KEYCLOAK_GROUP,
        payload = payloadSerializer.serialize(CreateTenantGroupPayload(tenantId = command.tenantId)),
        now = now,
      ),
    )

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.TENANT_CREATED,
        actorRef = command.creatorRef,
        tenantRef = command.tenantId,
        detail = mapOf("displayName" to command.displayName, "maxUsers" to tenant.quota.maxUsers),
      ),
    )
    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.MEMBERSHIP_GRANTED,
        actorRef = command.creatorRef,
        // 생성자 자신이 대상이다. 초대 없이 곧바로 관리자가 되는 유일한 경로라 명시해 남긴다.
        targetRef = command.creatorRef,
        tenantRef = command.tenantId,
        detail = mapOf("role" to TenantRole.tenantAdmin().value, "reason" to "tenant_creator"),
      ),
    )

    log.info("테넌트 생성 id={} creator={}", command.tenantId, command.creatorRef)
    return CreateTenantResult(
      tenantId = tenant.id.value,
      displayName = tenant.displayName,
      status = tenant.status.name,
      maxUsers = tenant.quota.maxUsers,
    )
  }
}

/**
 * 테넌트 생성 요청.
 *
 * `tenantId` 검증(slug 형식)은 [TenantId] VO 가 한다 — 형식 규칙이 도메인에 있어야
 * Keycloak group 경로와의 대응이 깨지지 않는다.
 */
data class CreateTenantCommand(
  val tenantId: String,
  val displayName: String,
  val creatorRef: String,
  /** `null` 이면 기본 쿼터를 쓴다. */
  val maxUsers: Int? = null,
)

data class CreateTenantResult(
  val tenantId: String,
  val displayName: String,
  val status: String,
  val maxUsers: Int?,
)

/** 같은 id 의 테넌트가 이미 있다. id 는 자연키라 재사용할 수 없다. */
class TenantAlreadyExistsException(
  val tenantId: String,
) : RuntimeException("이미 존재하는 테넌트입니다: $tenantId")
