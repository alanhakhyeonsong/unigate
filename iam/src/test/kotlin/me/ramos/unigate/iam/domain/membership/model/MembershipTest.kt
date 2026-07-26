package me.ramos.unigate.iam.domain.membership.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import me.ramos.unigate.iam.domain.membership.exception.MembershipDomainException
import me.ramos.unigate.iam.domain.membership.vo.TenantRole
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import java.time.Instant

/**
 * 멤버십 테스트 — **Keycloak group 이 표현하지 못하는 다대다 관계**의 규칙을 검증한다
 * (`IAM_PLATFORM_DECISION.md` §6.1).
 *
 * 특히 "초대는 쿼터를 차지하지 않는다"가 중요하다. 이걸 반대로 구현하면 초대만 뿌려도 테넌트가
 * 가득 차버린다.
 */
class MembershipTest :
  BehaviorSpec({
    val user = UserRef("user-1")
    val inviter = UserRef("admin-1")
    val tenant = TenantId("acme")
    val at = Instant.parse("2026-07-26T00:00:00Z")

    given("초대된 멤버십") {
      fun invited() = Membership.invite(user, tenant, TenantRole.member(), inviter, at)

      `when`("초대 직후를 보면") {
        val membership = invited()

        then("INVITED 이고 joinedAt 이 없다") {
          membership.status shouldBe MembershipStatus.INVITED
          membership.joinedAt.shouldBeNull()
        }

        then("쿼터를 차지하지 않는다 — 초대만으로 자리가 차면 안 된다") {
          membership.countsTowardQuota() shouldBe false
        }
      }

      `when`("수락하면") {
        val acceptedAt = Instant.parse("2026-07-27T00:00:00Z")
        val membership = invited().apply { accept(acceptedAt) }

        then("ACTIVE 가 되고 joinedAt 이 기록된다") {
          membership.status shouldBe MembershipStatus.ACTIVE
          membership.joinedAt shouldBe acceptedAt
        }

        then("이제 쿼터를 차지한다") {
          membership.countsTowardQuota() shouldBe true
        }
      }

      `when`("수락 전에 철회하면") {
        val membership = invited().apply { revoke() }

        then("REVOKED 가 된다 — 초대 취소도 같은 경로다") {
          membership.status shouldBe MembershipStatus.REVOKED
        }
      }
    }

    given("초대 없이 바로 합류한 멤버십 (테넌트 생성자)") {
      val membership = Membership.joinDirectly(user, tenant, TenantRole.tenantAdmin(), at)

      `when`("생성 직후를 보면") {
        then("곧바로 ACTIVE 이고 초대자가 없다 — 초대할 사람이 없는 경우다") {
          membership.status shouldBe MembershipStatus.ACTIVE
          membership.invitedBy.shouldBeNull()
          membership.joinedAt shouldBe at
        }
      }
    }

    given("철회된 멤버십 (종착)") {
      fun revoked() = Membership.invite(user, tenant, TenantRole.member(), inviter, at).apply { revoke() }

      `when`("다시 수락하려 하면") {
        then("거부된다 — 되살리려면 새 멤버십을 만들어야 한다") {
          shouldThrow<MembershipDomainException.InvalidStatusTransition> {
            revoked().accept(at)
          }
        }
      }

      `when`("역할을 바꾸려 하면") {
        then("거부된다 — 끝난 관계의 권한 조작은 감사를 혼란스럽게 한다") {
          shouldThrow<MembershipDomainException.RoleChangeOnRevoked> {
            revoked().changeRole(TenantRole.tenantAdmin())
          }
        }
      }
    }

    given("활성 멤버십의 역할 변경") {
      val membership = Membership.joinDirectly(user, tenant, TenantRole.member(), at)

      `when`("tenant-admin 으로 승격하면") {
        then("역할이 바뀐다") {
          membership.changeRole(TenantRole.tenantAdmin())
          membership.role shouldBe TenantRole.tenantAdmin()
        }
      }
    }

    given("역할 이름 형식") {
      `when`("Keycloak role 규칙에 어긋나면") {
        then("거부된다 — tenant-{id}-{role} 로 투영되기 때문") {
          shouldThrow<IllegalArgumentException> { TenantRole("Admin") } // 대문자
          shouldThrow<IllegalArgumentException> { TenantRole("a b") } // 공백
        }
      }
    }
  })
