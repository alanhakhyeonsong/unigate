package me.ramos.unigate.iam.domain.tenant.model

import me.ramos.unigate.iam.domain.tenant.enums.TenantStatus
import me.ramos.unigate.iam.domain.tenant.exception.TenantDomainException
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import me.ramos.unigate.iam.domain.tenant.vo.TenantQuota

/**
 * 테넌트 애그리거트 루트.
 *
 * ## 이 클래스가 IAM의 존재 이유다
 * Keycloak은 group을 알지만 **테넌트를 모른다.** 상태기계(정지·보관), 쿼터, 수명주기는 전부 여기 있는
 * 비즈니스 규칙이고 Keycloak에는 대응물이 없다. Keycloak group `/tenants/{id}`는 토큰 claim을 만들기
 * 위한 **투영본**일 뿐이다(`IAM_PLATFORM_DECISION.md` §6.1).
 *
 * ## 불변 설계
 * 상태 변경은 도메인 메서드로만 하고 **새 인스턴스를 반환**한다(`domain-model` skill 규칙 2).
 * 애그리거트가 자기 규칙을 스스로 지키므로, 서비스가 상태를 직접 대입해 규칙을 우회할 길이 없다.
 *
 * ## 멤버 수를 필드로 갖지 않는 이유
 * `memberCount`를 이 애그리거트 안에 두면 멤버 추가/삭제마다 테넌트를 갱신해야 하고, 동시 가입 시
 * 경합이 생긴다. 대신 쿼터 검사([ensureCanAcceptMember])가 **현재 멤버 수를 인자로 받는다** —
 * 세는 책임은 Membership 쪽에 두고, 규칙 판단만 여기서 한다.
 */
class Tenant private constructor(
  val id: TenantId,
  val displayName: String,
  status: TenantStatus,
  quota: TenantQuota,
) {
  var status: TenantStatus = status
    private set

  var quota: TenantQuota = quota
    private set

  init {
    require(displayName.isNotBlank()) { "테넌트 표시 이름은 비어 있을 수 없습니다" }
  }

  /**
   * 상태를 [target]으로 전이한다. 전이 규칙은 [TenantStatus.canTransitionTo]가 안다.
   *
   * @throws TenantDomainException.InvalidStatusTransition 허용되지 않는 전이
   */
  fun transitionTo(target: TenantStatus) {
    if (!status.canTransitionTo(target)) {
      throw TenantDomainException.InvalidStatusTransition(status, target)
    }
    status = target
  }

  /** 프로비저닝 완료 → 사용 가능. */
  fun activate() = transitionTo(TenantStatus.ACTIVE)

  /** 일시 정지. 기존 멤버는 유지되지만 신규 유입이 막힌다. */
  fun suspend() = transitionTo(TenantStatus.SUSPENDED)

  /** 보관(종착). 이후 어떤 전이도 불가능하다. */
  fun archive() = transitionTo(TenantStatus.ARCHIVED)

  /**
   * 쿼터를 변경한다. 이미 초과 상태여도 **막지 않는다** — 운영상 쿼터를 낮춰야 하는 경우가 있고,
   * 기존 멤버를 강제로 쫓아내는 것은 별도 결정이어야 하기 때문이다. 초과분은 신규 가입만 막힌다.
   */
  fun changeQuota(newQuota: TenantQuota) {
    quota = newQuota
  }

  /**
   * 새 멤버를 받을 수 있는지 검사하고, 불가하면 예외를 던진다.
   *
   * 두 가지를 함께 본다 — **상태**(ACTIVE인가)와 **쿼터**(자리가 남았는가). 순서가 의미 있다:
   * SUSPENDED 테넌트에 자리가 남아 있어도 거부해야 하므로 상태를 먼저 본다.
   *
   * @param currentMemberCount 현재 활성 멤버 수 (세는 책임은 이 애그리거트 밖에 있다)
   */
  fun ensureCanAcceptMember(currentMemberCount: Int) {
    if (!status.acceptsNewMember()) {
      throw TenantDomainException.NotAcceptingMembers(status)
    }
    if (!quota.allowsAdditionalMember(currentMemberCount)) {
      throw TenantDomainException.QuotaExceeded(quota.maxUsers ?: error("무제한 쿼터가 초과될 수 없습니다"))
    }
  }

  companion object {
    /**
     * 신규 테넌트 생성. **`PENDING`으로 시작한다** — Keycloak group 생성 등 외부 프로비저닝이
     * 끝나야 쓸 수 있기 때문이다. 곧바로 ACTIVE로 만들면 group이 없는 테넌트에 멤버를 넣게 된다.
     */
    fun create(
      id: TenantId,
      displayName: String,
      quota: TenantQuota = TenantQuota.defaultQuota(),
    ): Tenant =
      Tenant(
        id = id,
        displayName = displayName,
        status = TenantStatus.PENDING,
        quota = quota,
      )

    /** 저장소에서 복원. 전이 규칙을 거치지 않고 상태를 그대로 되살린다. */
    fun restore(
      id: TenantId,
      displayName: String,
      status: TenantStatus,
      quota: TenantQuota,
    ): Tenant =
      Tenant(
        id = id,
        displayName = displayName,
        status = status,
        quota = quota,
      )
  }
}
