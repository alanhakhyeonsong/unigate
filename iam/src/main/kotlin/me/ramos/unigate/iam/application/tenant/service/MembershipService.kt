package me.ramos.unigate.iam.application.tenant.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.tenant.dto.GroupMembershipPayload
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import me.ramos.unigate.iam.domain.membership.model.Membership
import me.ramos.unigate.iam.domain.membership.vo.TenantRole
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 멤버십 수명주기 — 초대 · 수락 · 역할변경 · 해제 (Phase 9d).
 *
 * ## 쿼터를 **수락 시점**에 검사한다
 * 초대는 자리를 차지하지 않는다(`MembershipStatus.countsTowardQuota`). 초대 때 검사하면
 * 10명 정원에 10명을 초대해두고 아무도 수락하지 않은 상태에서 **실제 멤버는 0명인데 더 못 부르는**
 * 상황이 된다. 반대로 수락 때만 보면 초대는 얼마든지 하되 **먼저 수락한 사람이 자리를 갖는다.**
 *
 * 그 대가는 **초대받고도 못 들어올 수 있다**는 것이다. 정원이 찬 뒤 수락하면 거부된다 —
 * 이건 결함이 아니라 선택이며, 사용자에게 그 사유(`quota_exceeded`)를 분명히 돌려준다.
 *
 * ## Keycloak 투영 시점
 * ```
 * 초대(INVITED)  → 투영 없음   — 아직 멤버가 아니다. claim 에 실리면 안 된다
 * 수락(ACTIVE)   → group 추가
 * 해제(REVOKED)  → group 제거
 * 역할변경        → 투영 없음   — P9d 의 투영은 group 소속까지다(역할 claim 은 P9e)
 * ```
 *
 * ## 행위자와 대상이 **다르다**
 * 지금까지 대부분의 감사는 `actor == target` 이었지만 여기서는 관리자가 남의 멤버십을 다룬다.
 * 유일한 예외가 [accept] 로, 그것만 본인이 자기 것에 하는 행위다.
 */
@Service
class MembershipService(
  private val tenantRepository: TenantRepositoryPort,
  private val outboxPort: OutboxPort,
  private val payloadSerializer: PayloadSerializerPort,
  private val recordAuditEventOutPort: RecordAuditEventOutPort,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 내 멤버십 목록 (수락 대기 중인 초대 포함).
   *
   * ## 토큰의 `groups` claim 으로는 부족하다
   * claim 은 **발급 시점의 ACTIVE 소속**뿐이다. 그래서 세 가지가 안 보인다:
   * ```
   * ① 수락 대기 중인 초대(INVITED)     — claim 에 애초에 없다
   * ② 방금 수락한 테넌트               — 재로그인 전까지 claim 이 옛 상태다
   * ③ 방금 해제된 테넌트               — 토큰 만료 전까지 claim 에 남아 있다
   * ```
   * 그래서 화면이 "초대가 있다"·"수락했으니 재로그인하라" 를 말하려면 **도메인 쪽 목록**이
   * 필요하다. 게이트의 인가 판단은 여전히 claim 으로만 한다(그건 바뀌지 않는다) —
   * 이 목록은 **보여주기 위한 것**이지 권한의 근거가 아니다.
   *
   * `REVOKED` 는 제외한다. 해제된 소속을 목록에 남기면 "지금 쓸 수 있는 것" 과 이력이 섞인다.
   */
  @Transactional(readOnly = true)
  fun listMine(userRef: String): List<MyMembershipResult> {
    val memberships = tenantRepository.findMembershipsOf(UserRef(userRef))
    return memberships
      .filterNot { it.status == MembershipStatus.REVOKED }
      .map { membership ->
        // 테넌트 표시 이름은 목록의 핵심 정보다. 없으면 화면이 id 만 보여주게 된다.
        val tenant = tenantRepository.findById(membership.tenantId)
        MyMembershipResult(
          tenantId = membership.tenantId.value,
          tenantDisplayName = tenant?.displayName,
          tenantStatus = tenant?.status?.name,
          role = membership.role.value,
          status = membership.status.name,
          joinedAt = membership.joinedAt,
        )
      }
  }

  /**
   * 사용자를 테넌트에 초대한다. `INVITED` 로 시작하며 **쿼터를 차지하지 않는다.**
   *
   * @throws TenantNotFoundException 테넌트가 없다
   * @throws MembershipAlreadyExistsException 이미 유효한 멤버십이 있다(초대 중이거나 이미 멤버)
   */

  @Transactional
  fun invite(command: InviteMemberCommand): MembershipResult {
    val tenantId = TenantId(command.tenantId)
    val userRef = UserRef(command.userRef)
    val tenant = tenantRepository.findById(tenantId) ?: throw TenantNotFoundException(command.tenantId)

    // ⚠️ 테넌트 상태는 **초대 시점에도** 본다. 쿼터와 달리 상태는 "지금 이 테넌트가 살아 있는가" 라,
    // SUSPENDED/ARCHIVED 테넌트로 사람을 부르는 것 자체가 말이 안 된다.
    // (`ensureCanAcceptMember` 는 쿼터까지 함께 보므로 여기서는 상태만 확인한다.)
    if (!tenant.status.acceptsNewMember()) {
      throw TenantNotAcceptingMembersException(command.tenantId, tenant.status.name)
    }

    tenantRepository.findActiveOrInvited(tenantId, userRef)?.let {
      throw MembershipAlreadyExistsException(command.tenantId, command.userRef, it.status.name)
    }

    val now = Instant.now(clock)
    val membership =
      Membership.invite(
        userRef = userRef,
        tenantId = tenantId,
        role = TenantRole(command.role),
        invitedBy = UserRef(command.actorRef),
        invitedAt = now,
      )
    val saved = tenantRepository.saveMembership(membership)

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.MEMBERSHIP_INVITED,
        actorRef = command.actorRef,
        targetRef = command.userRef,
        tenantRef = command.tenantId,
        detail = mapOf("role" to command.role),
      ),
    )
    log.info("멤버 초대 tenant={} user={} actor={}", command.tenantId, command.userRef, command.actorRef)
    return saved.toResult()
  }

  /**
   * 초대를 수락한다 — **본인만** 할 수 있다.
   *
   * 대상을 토큰 `sub` 로만 정하므로 인가 검사가 따로 필요 없다(P8e 와 같은 IDOR-free 구조).
   * 관리 API 가 아닌 이유가 이것이다.
   *
   * @throws MembershipNotFoundException 초대가 없다
   * @throws TenantDomainException.QuotaExceeded 정원이 찼다 — **수락 시점**에 검사한다
   */
  @Transactional
  fun accept(
    tenantIdValue: String,
    userRefValue: String,
  ): MembershipResult {
    val tenantId = TenantId(tenantIdValue)
    val userRef = UserRef(userRefValue)
    val tenant = tenantRepository.findById(tenantId) ?: throw TenantNotFoundException(tenantIdValue)
    val membership =
      tenantRepository.findActiveOrInvited(tenantId, userRef)
        ?: throw MembershipNotFoundException(tenantIdValue, userRefValue)

    // 자리가 남았는가 — 상태(ACTIVE 인가)와 쿼터를 도메인이 함께 본다.
    // 초대 때가 아니라 여기서 보는 이유는 클래스 KDoc 참조.
    tenant.ensureCanAcceptMember(tenantRepository.countActiveMembers(tenantId))

    membership.accept(Instant.now(clock))
    val saved = tenantRepository.updateMembership(membership)

    // 이제서야 실제 멤버다 → Keycloak group 에 반영한다.
    enqueueGroupSync(OutboxEventType.ADD_GROUP_MEMBER, tenantIdValue, userRefValue)

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.MEMBERSHIP_ACCEPTED,
        // 본인이 본인 것에 한 행위 — actor 와 target 이 같은 몇 안 되는 관리 사건이다.
        actorRef = userRefValue,
        targetRef = userRefValue,
        tenantRef = tenantIdValue,
        detail = mapOf("role" to membership.role.value),
      ),
    )
    log.info("초대 수락 tenant={} user={}", tenantIdValue, userRefValue)
    return saved.toResult()
  }

  /**
   * 멤버의 역할을 바꾼다.
   *
   * Keycloak 에 투영하지 않는다 — P9d 의 투영은 group 소속까지이고, 역할은 IAM DB 가 SoT 다.
   * 토큰 claim 으로 내보내는 것은 P9e 의 몫이다.
   */
  @Transactional
  fun changeRole(command: ChangeRoleCommand): MembershipResult {
    val tenantId = TenantId(command.tenantId)
    val userRef = UserRef(command.userRef)
    val membership =
      tenantRepository.findActiveOrInvited(tenantId, userRef)
        ?: throw MembershipNotFoundException(command.tenantId, command.userRef)

    val previousRole = membership.role.value
    // REVOKED 멤버십의 역할 변경은 도메인이 거부한다(RoleChangeOnRevoked).
    // 여기까지 온 것은 findActiveOrInvited 가 이미 걸렀다는 뜻이지만, 규칙의 주인은 도메인이다.
    membership.changeRole(TenantRole(command.role))
    val saved = tenantRepository.updateMembership(membership)

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.MEMBERSHIP_ROLE_CHANGED,
        actorRef = command.actorRef,
        targetRef = command.userRef,
        tenantRef = command.tenantId,
        // 변경 **전후**를 함께 남긴다. 권한 변화는 "무엇이 되었나" 만으로는 감사가 안 된다.
        detail = mapOf("before" to previousRole, "after" to command.role),
      ),
    )
    log.info("역할 변경 tenant={} user={} {} -> {}", command.tenantId, command.userRef, previousRole, command.role)
    return saved.toResult()
  }

  /**
   * 멤버십을 해제한다(초대 취소 · 멤버 제거 양쪽).
   *
   * ⚠️ **권한 회수에는 지연이 있다.** group 제거가 outbox 를 거치고, 이미 발급된 토큰은 만료
   * (현재 5분) 전까지 유효하다. 즉 "지금 즉시 차단" 이 아니다. 즉시성이 필요하면 다운스트림
   * introspection 이나 back-channel logout 이 필요하며, 그건 별도 결정이다
   * (`IAM_PLATFORM_DECISION.md` §14).
   */
  @Transactional
  fun revoke(command: RevokeMembershipCommand): MembershipResult {
    val tenantId = TenantId(command.tenantId)
    val userRef = UserRef(command.userRef)
    val membership =
      tenantRepository.findActiveOrInvited(tenantId, userRef)
        ?: throw MembershipNotFoundException(command.tenantId, command.userRef)

    val wasActive = membership.countsTowardQuota()
    membership.revoke()
    val saved = tenantRepository.updateMembership(membership)

    // 초대만 취소한 경우에는 group 에 넣은 적이 없으므로 제거할 것도 없다.
    // 불필요한 지시를 만들면 워커가 "없는 것을 지우는" 호출을 반복한다.
    if (wasActive) {
      enqueueGroupSync(OutboxEventType.REMOVE_GROUP_MEMBER, command.tenantId, command.userRef)
    }

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.MEMBERSHIP_REVOKED,
        actorRef = command.actorRef,
        targetRef = command.userRef,
        tenantRef = command.tenantId,
        detail = mapOf("wasActive" to wasActive, "role" to membership.role.value),
      ),
    )
    log.info("멤버십 해제 tenant={} user={} actor={}", command.tenantId, command.userRef, command.actorRef)
    return saved.toResult()
  }

  @Transactional(readOnly = true)
  fun list(tenantIdValue: String): List<MembershipResult> {
    val tenantId = TenantId(tenantIdValue)
    if (tenantRepository.findById(tenantId) == null) {
      throw TenantNotFoundException(tenantIdValue)
    }
    return tenantRepository.findMemberships(tenantId).map { it.toResult() }
  }

  private fun enqueueGroupSync(
    eventType: OutboxEventType,
    tenantId: String,
    userRef: String,
  ) {
    outboxPort.enqueue(
      OutboxRecord.pending(
        eventType = eventType,
        payload = payloadSerializer.serialize(GroupMembershipPayload(tenantId = tenantId, userRef = userRef)),
        now = Instant.now(clock),
      ),
    )
  }

  private fun Membership.toResult() =
    MembershipResult(
      tenantId = tenantId.value,
      userRef = userRef.value,
      role = role.value,
      status = status.name,
      joinedAt = joinedAt,
    )
}

data class InviteMemberCommand(
  val tenantId: String,
  val userRef: String,
  val role: String,
  /** 초대한 관리자의 `sub`. 감사의 행위자이자 `invitedBy` 가 된다. */
  val actorRef: String,
)

data class ChangeRoleCommand(
  val tenantId: String,
  val userRef: String,
  val role: String,
  val actorRef: String,
)

data class RevokeMembershipCommand(
  val tenantId: String,
  val userRef: String,
  val actorRef: String,
)

/**
 * 내 멤버십 한 건 — **화면을 위한 모델**이지 인가의 근거가 아니다.
 *
 * 테넌트 표시 이름·상태를 함께 담는다. id 만 주면 화면이 테넌트마다 추가 조회를 하게 되고,
 * 그 조회는 관리 API(`/iam/admin`)라 일반 사용자가 부를 수 없다.
 */
data class MyMembershipResult(
  val tenantId: String,
  val tenantDisplayName: String?,
  val tenantStatus: String?,
  val role: String,
  val status: String,
  val joinedAt: Instant?,
)

data class MembershipResult(
  val tenantId: String,
  val userRef: String,
  val role: String,
  val status: String,
  val joinedAt: Instant?,
)

class TenantNotFoundException(
  val tenantId: String,
) : RuntimeException("테넌트를 찾을 수 없습니다: $tenantId")

class TenantNotAcceptingMembersException(
  val tenantId: String,
  val status: String,
) : RuntimeException("새 멤버를 받을 수 없는 테넌트입니다: $tenantId (status=$status)")

class MembershipAlreadyExistsException(
  val tenantId: String,
  val userRef: String,
  val status: String,
) : RuntimeException("이미 멤버십이 있습니다: tenant=$tenantId status=$status")

class MembershipNotFoundException(
  val tenantId: String,
  val userRef: String,
) : RuntimeException("멤버십을 찾을 수 없습니다: tenant=$tenantId")
