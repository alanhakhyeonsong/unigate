package me.ramos.unigate.iam.domain.tenant.enums

/**
 * 테넌트 수명주기 상태.
 *
 * **Keycloak에는 이런 개념이 없다.** group은 있어도 "정지된 테넌트"나 "보관된 테넌트" 같은 상태를 모른다.
 * 이 상태기계가 IAM이 얇은 패스스루가 아니라는 근거 중 하나다(`IAM_PLATFORM_DECISION.md` §6).
 *
 * ```
 * PENDING ──활성화──> ACTIVE <──재개── SUSPENDED
 *    │                  │  └──정지──────> │
 *    │                  │                 │
 *    └──────────────> ARCHIVED <──────────┘
 * ```
 *
 * `ARCHIVED`는 **종착 상태**다. 되돌리려면 새 테넌트를 만들어야 한다 — 보관된 테넌트를 되살리면
 * 그 사이 회수된 리소스·이관된 멤버십과 어긋나기 때문이다.
 */
enum class TenantStatus {
  /** 생성됐으나 아직 쓸 수 없다. 초기 프로비저닝(Keycloak group 생성 등) 대기. */
  PENDING,

  /** 정상 사용 가능. 멤버를 받을 수 있는 **유일한** 상태다. */
  ACTIVE,

  /** 일시 정지(미납·정책 위반 등). 기존 멤버는 남지만 **새 멤버를 받지 못한다.** */
  SUSPENDED,

  /** 보관(종착). 더 이상 어떤 전이도 허용하지 않는다. */
  ARCHIVED,
  ;

  /** 이 상태에서 [target]으로 갈 수 있는가. 전이 규칙을 상태 자신이 안다. */
  fun canTransitionTo(target: TenantStatus): Boolean =
    when (this) {
      PENDING -> target == ACTIVE || target == ARCHIVED
      ACTIVE -> target == SUSPENDED || target == ARCHIVED
      SUSPENDED -> target == ACTIVE || target == ARCHIVED
      ARCHIVED -> false
    }

  /** 새 멤버를 받을 수 있는 상태인가. `ACTIVE`만 허용한다. */
  fun acceptsNewMember(): Boolean = this == ACTIVE
}
