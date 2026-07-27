package me.ramos.unigate.iam.application.tenant.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.domain.membership.model.Membership
import me.ramos.unigate.iam.domain.membership.vo.TenantRole
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.model.Tenant
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import java.time.Clock
import java.time.Instant

/**
 * "내 멤버십 목록" 단위 테스트.
 *
 * 이 목록의 존재 이유는 **토큰 claim 과 다른 것을 보여준다**는 데 있다. 그 차이를 고정한다 —
 * 초대는 claim 에 애초에 없고, 해제는 토큰 만료 전까지 claim 에 남아 있다.
 *
 * 계층: L1(단위).
 */
class MyMembershipListTest :
  BehaviorSpec({
    val repository = mockk<TenantRepositoryPort>(relaxed = true)
    val service =
      MembershipService(
        tenantRepository = repository,
        outboxPort = mockk(relaxed = true),
        payloadSerializer = mockk(relaxed = true),
        recordAuditEventOutPort = mockk(relaxed = true),
        clock = Clock.systemUTC(),
      )

    val me = UserRef("kc-me")
    val now = Instant.parse("2026-07-27T00:00:00Z")

    fun membership(
      tenantId: String,
      role: String,
    ) = Membership.invite(
      userRef = me,
      tenantId = TenantId(tenantId),
      role = TenantRole(role),
      invitedBy = me,
      invitedAt = now,
    )

    given("초대 · 활성 · 해제가 섞인 사용자") {
      val invited = membership("globex", "tenant-member")
      val active = membership("acme", "tenant-admin").apply { accept(now) }
      val revoked =
        membership("gone", "tenant-member").apply {
          accept(now)
          revoke()
        }

      every { repository.findMembershipsOf(me) } returns listOf(invited, active, revoked)
      // ⚠️ **`any()` 를 쓰지 않는다.** `TenantId` 는 형식(slug)을 강제하는 value class 인데,
      // mockk 는 시그니처를 만들려고 **임의의 문자열로 인스턴스를 만들어 본다.** 그 값이
      // 검증에 걸려 터진다(`initializationError`). 게다가 단독 실행에서는 통과하고
      // **전체 스위트에서만** 깨져서(생성 값이 실행 순서를 탄다) 원인 찾기가 고약하다.
      //
      // 구체 값으로 스텁하면 더미 인스턴스를 만들 이유가 사라진다.
      every { repository.findById(TenantId("acme")) } returns
        Tenant.create(id = TenantId("acme"), displayName = "acme 회사")
      every { repository.findById(TenantId("globex")) } returns
        Tenant.create(id = TenantId("globex"), displayName = "globex 회사")

      `when`("목록을 조회하면") {
        val result = service.listMine(me.value)

        then("해제된 것은 빠진다 — 지금 쓸 수 있는 것과 이력이 섞이면 안 된다") {
          result.map { it.tenantId } shouldContainExactlyInAnyOrder listOf("globex", "acme")
        }

        then("**수락 대기 중인 초대가 보인다** — 토큰 claim 에는 없는 정보다") {
          result.first { it.tenantId == "globex" }.status shouldBe "INVITED"
        }

        then("테넌트 표시 이름을 함께 준다 — 없으면 화면이 id 만 보여주게 된다") {
          result.first { it.tenantId == "acme" }.tenantDisplayName shouldBe "acme 회사"
        }
      }
    }
  })
