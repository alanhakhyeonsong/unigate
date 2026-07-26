package me.ramos.unigate.iam.domain.tenant.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import me.ramos.unigate.iam.domain.tenant.enums.TenantStatus
import me.ramos.unigate.iam.domain.tenant.exception.TenantDomainException
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import me.ramos.unigate.iam.domain.tenant.vo.TenantQuota

/**
 * 테넌트 애그리거트의 **상태기계와 쿼터 규칙** 테스트.
 *
 * 이 규칙들이 "IAM 이 얇은 패스스루가 아니다"의 실체다(`IAM_PLATFORM_DECISION.md` §6) —
 * Keycloak 에는 대응물이 없는 순수 비즈니스 로직이라 여기서 못 박는다.
 *
 * 계층: L1 단위 테스트 → Kotest BehaviorSpec (testing skill 규칙 2).
 */
class TenantTest :
  BehaviorSpec({
    val tenantId = TenantId("acme")

    given("새로 만든 테넌트") {
      `when`("생성 직후 상태를 보면") {
        val tenant = Tenant.create(tenantId, "ACME Inc.")

        then("PENDING 이다 — 외부 프로비저닝 전이라 아직 쓸 수 없다") {
          tenant.status shouldBe TenantStatus.PENDING
        }

        then("멤버를 받지 못한다") {
          shouldThrow<TenantDomainException.NotAcceptingMembers> {
            tenant.ensureCanAcceptMember(currentMemberCount = 0)
          }
        }
      }

      `when`("활성화하면") {
        val tenant = Tenant.create(tenantId, "ACME Inc.").apply { activate() }

        then("ACTIVE 가 되고 멤버를 받을 수 있다") {
          tenant.status shouldBe TenantStatus.ACTIVE
          tenant.ensureCanAcceptMember(currentMemberCount = 0)
        }
      }
    }

    given("ACTIVE 테넌트") {
      fun activeTenant(quota: TenantQuota = TenantQuota.defaultQuota()) =
        Tenant.create(tenantId, "ACME Inc.", quota).apply { activate() }

      `when`("정지시키면") {
        val tenant = activeTenant().apply { suspend() }

        then("SUSPENDED 가 되고 신규 멤버를 거부한다 — 자리가 남아 있어도 마찬가지다") {
          tenant.status shouldBe TenantStatus.SUSPENDED
          shouldThrow<TenantDomainException.NotAcceptingMembers> {
            tenant.ensureCanAcceptMember(currentMemberCount = 0)
          }
        }

        then("다시 활성화할 수 있다") {
          tenant.activate()
          tenant.status shouldBe TenantStatus.ACTIVE
        }
      }

      `when`("쿼터 경계에 도달하면") {
        val tenant = activeTenant(TenantQuota(maxUsers = 3))

        then("한 자리 남았으면 받는다") {
          tenant.ensureCanAcceptMember(currentMemberCount = 2)
        }

        then("정확히 가득 찼으면 거부한다 — 경계값(초과가 아니라 도달)") {
          shouldThrow<TenantDomainException.QuotaExceeded> {
            tenant.ensureCanAcceptMember(currentMemberCount = 3)
          }
        }
      }

      `when`("무제한 쿼터이면") {
        val tenant = activeTenant(TenantQuota.unlimited())

        then("아무리 많아도 받는다") {
          tenant.ensureCanAcceptMember(currentMemberCount = 100_000)
        }
      }

      `when`("이미 초과한 상태로 쿼터를 낮추면") {
        val tenant = activeTenant(TenantQuota(maxUsers = 10))

        then("쿼터 변경 자체는 허용된다 — 기존 멤버 축출은 별도 결정이다") {
          tenant.changeQuota(TenantQuota(maxUsers = 2))
          tenant.quota.maxUsers shouldBe 2
        }

        then("다만 신규 가입은 막힌다") {
          tenant.changeQuota(TenantQuota(maxUsers = 2))
          shouldThrow<TenantDomainException.QuotaExceeded> {
            tenant.ensureCanAcceptMember(currentMemberCount = 5)
          }
        }
      }
    }

    given("ARCHIVED 테넌트 (종착 상태)") {
      val archived = Tenant.create(tenantId, "ACME Inc.").apply { archive() }

      `when`("어떤 전이든 시도하면") {
        then("모두 거부된다 — 되살리려면 새 테넌트를 만들어야 한다") {
          shouldThrow<TenantDomainException.InvalidStatusTransition> { archived.activate() }
          shouldThrow<TenantDomainException.InvalidStatusTransition> { archived.suspend() }
          shouldThrow<TenantDomainException.InvalidStatusTransition> { archived.archive() }
        }
      }
    }

    given("잘못된 입력") {
      `when`("테넌트 ID 형식이 어긋나면") {
        then("생성 시점에 거부된다 — Keycloak group 경로가 깨지기 때문") {
          shouldThrow<IllegalArgumentException> { TenantId("ACME") } // 대문자
          shouldThrow<IllegalArgumentException> { TenantId("a/b") } // 슬래시
          shouldThrow<IllegalArgumentException> { TenantId("a") } // too short
        }
      }

      `when`("표시 이름이 비면") {
        then("거부된다") {
          shouldThrow<IllegalArgumentException> { Tenant.create(tenantId, "  ") }
        }
      }

      `when`("쿼터가 0 이하이면") {
        then("거부된다 — 아무도 못 들어오는 테넌트는 의미가 없다") {
          shouldThrow<IllegalArgumentException> { TenantQuota(maxUsers = 0) }
        }
      }
    }

    given("group 경로 투영") {
      `when`("toGroupPath 를 부르면") {
        then("Keycloak group 경로 형태가 된다 — 게이트웨이 파싱의 전제다") {
          TenantId("acme").toGroupPath() shouldBe "/tenants/acme"
        }
      }
    }
  })
