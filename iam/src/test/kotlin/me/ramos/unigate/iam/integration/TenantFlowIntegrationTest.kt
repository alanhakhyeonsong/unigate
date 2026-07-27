package me.ramos.unigate.iam.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import me.ramos.unigate.iam.adapter.jpaOut.repository.AuditLogJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.MembershipJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.TenantJpaRepository
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.application.tenant.service.CreateTenantCommand
import me.ramos.unigate.iam.application.tenant.service.CreateTenantService
import me.ramos.unigate.iam.application.tenant.service.TenantAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import me.ramos.unigate.iam.domain.tenant.enums.TenantStatus
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/**
 * 테넌트 온보딩 통합 테스트 — 실제 PostgreSQL (Phase 9c-2).
 *
 * ## 여기서만 확인되는 것
 * 1. **한 트랜잭션**에 tenant·membership·outbox·감사가 함께 들어간다 — 트랜잭션이 진짜여야 보인다
 * 2. **부분 unique 인덱스**(`uq_membership_active`)가 실제로 동작한다 — DB 제약이라 mock 으로는 못 본다
 * 3. 워커가 group 을 만든 뒤 **ACTIVE 로 전이**한다 — outbox 의 두 번째 사용처가 실제로 도는가
 */
@Tag("testcontainers")
@SpringBootTest(properties = ["unigate.iam.outbox.polling.enabled=false"])
@TestPropertySource(
  properties = [
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:3000/unigate_iam_test",
    "spring.datasource.username=testuser",
    "spring.datasource.password=testpass",
    "spring.flyway.url=jdbc:postgresql://127.0.0.1:3000/unigate_iam_test",
    "spring.flyway.user=testuser",
    "spring.flyway.password=testpass",
    "unigate.iam.keycloak.server-url=http://localhost:1",
  ],
)
class TenantFlowIntegrationTest {
  @Autowired
  private lateinit var createTenantService: CreateTenantService

  @Autowired
  private lateinit var outboxProcessor: OutboxProcessor

  @Autowired
  private lateinit var tenantRepositoryPort: TenantRepositoryPort

  @Autowired
  private lateinit var tenantRepository: TenantJpaRepository

  @Autowired
  private lateinit var membershipRepository: MembershipJpaRepository

  @Autowired
  private lateinit var outboxRepository: OutboxRecordJpaRepository

  @Autowired
  private lateinit var auditLogRepository: AuditLogJpaRepository

  @MockkBean
  private lateinit var identityProviderPort: IdentityProviderPort

  @BeforeEach
  fun cleanUp() {
    auditLogRepository.deleteAll()
    outboxRepository.deleteAll()
    membershipRepository.deleteAll()
    tenantRepository.deleteAll()
  }

  @Test
  fun `테넌트를 만들면 멤버십·outbox 지시·감사가 같은 커밋에 남는다`() {
    val result = createTenantService.create(command("acme"))

    // 응답 시점에는 **아직 쓸 수 없다** — group 프로비저닝이 끝나야 ACTIVE 다.
    assertThat(result.status).isEqualTo(TenantStatus.PENDING.name)

    assertThat(tenantRepository.findAll()).hasSize(1)

    // 생성자가 곧바로 활성 tenant-admin 이다. 초대 절차가 없는 유일한 경로 —
    // 그렇지 않으면 아무도 손댈 수 없는 테넌트가 만들어진다.
    val membership = membershipRepository.findAll().single()
    assertThat(membership.userRef).isEqualTo(CREATOR)
    assertThat(membership.role).isEqualTo("tenant-admin")
    assertThat(membership.status).isEqualTo(MembershipStatus.ACTIVE)
    assertThat(membership.joinedAt).isNotNull()

    // 지시가 **둘**이다: group 생성 + 생성자를 그 group 에 넣기.
    // 후자가 없으면 테넌트를 만든 사람이 정작 그 테넌트에 접근하지 못한다(P9e 에서 발견).
    val instructions = outboxRepository.findAll()
    assertThat(instructions.map { it.eventType }).containsExactlyInAnyOrder(
      OutboxEventType.CREATE_KEYCLOAK_GROUP,
      OutboxEventType.ADD_GROUP_MEMBER,
    )
    assertThat(instructions).allMatch { it.status == OutboxStatus.PENDING }

    val events = auditLogRepository.findAll().map { it.eventType }
    assertThat(events).containsExactlyInAnyOrder(
      AuditEventType.TENANT_CREATED,
      AuditEventType.MEMBERSHIP_GRANTED,
    )
    // 감사에 tenant 축이 채워진다 — P9a 에서 미리 넣어둔 컬럼의 **첫 실사용**이다.
    assertThat(auditLogRepository.findAll()).allMatch { it.tenantRef == "acme" }
  }

  @Test
  fun `워커가 group 을 만들면 테넌트가 ACTIVE 가 되고 생성자도 group 에 들어간다`() {
    every { identityProviderPort.createTenantGroup("acme") } returns Unit
    every { identityProviderPort.addUserToTenantGroup(any(), any()) } returns Unit
    createTenantService.create(command("acme"))

    // 지시가 둘이므로 두 번 돌린다.
    assertThat(outboxProcessor.processOne()).isTrue()
    assertThat(outboxProcessor.processOne()).isTrue()

    verify { identityProviderPort.createTenantGroup("acme") }
    // 생성자가 group 에 들어가야 토큰의 groups claim 에 이 테넌트가 실린다(P9e).
    verify { identityProviderPort.addUserToTenantGroup("acme", CREATOR) }

    assertThat(tenantRepositoryPort.findById(TenantId("acme"))?.status).isEqualTo(TenantStatus.ACTIVE)
    assertThat(outboxRepository.findAll()).allMatch { it.status == OutboxStatus.COMPLETED }
    assertThat(auditLogRepository.findAll().map { it.eventType })
      .contains(AuditEventType.TENANT_ACTIVATED)
  }

  @Test
  fun `group 생성이 실패하면 테넌트는 PENDING 에 머문다`() {
    // 프로비저닝이 안 끝났는데 ACTIVE 로 만들면 **group 없는 테넌트에 멤버를 넣게 된다.**
    every { identityProviderPort.createTenantGroup(any()) } throws
      IdentityProviderUnavailableException("keycloak down")
    every { identityProviderPort.addUserToTenantGroup(any(), any()) } throws
      IdentityProviderUnavailableException("group 이 아직 없다")
    createTenantService.create(command("acme"))

    outboxProcessor.processOne()

    assertThat(tenantRepositoryPort.findById(TenantId("acme"))?.status).isEqualTo(TenantStatus.PENDING)
    // 재시도 대상으로 남는다 — 외부 장애로 테넌트를 잃지 않는다.
    assertThat(outboxRepository.findAll()).allMatch { it.status == OutboxStatus.PENDING }
  }

  @Test
  fun `같은 지시가 두 번 처리돼도 터지지 않는다`() {
    // outbox 는 최소 1회 실행이다. ACTIVE→ACTIVE 전이를 시도하면 상태기계가 거부해
    // **성공한 작업이 실패로 기록**된다(markProfileIdentityFailed 에서 겪은 것과 같은 함정).
    every { identityProviderPort.createTenantGroup("acme") } returns Unit
    every { identityProviderPort.addUserToTenantGroup(any(), any()) } returns Unit
    createTenantService.create(command("acme"))
    outboxProcessor.processOne()
    outboxProcessor.processOne()

    // 운영자가 group 생성 지시를 재처리한 상황을 흉내 낸다.
    val record = outboxRepository.findAll().single { it.eventType == OutboxEventType.CREATE_KEYCLOAK_GROUP }
    record.status = OutboxStatus.PENDING
    outboxRepository.save(record)

    assertThat(outboxProcessor.processOne()).isTrue()
    assertThat(outboxRepository.findAll()).allMatch { it.status == OutboxStatus.COMPLETED }
    assertThat(tenantRepositoryPort.findById(TenantId("acme"))?.status).isEqualTo(TenantStatus.ACTIVE)
  }

  @Test
  fun `같은 id 로 두 번 만들 수 없다`() {
    createTenantService.create(command("acme"))

    assertThatThrownBy { createTenantService.create(command("acme")) }
      .isInstanceOf(TenantAlreadyExistsException::class.java)

    assertThat(tenantRepository.findAll()).hasSize(1)
  }

  @Test
  fun `형식에 맞지 않는 테넌트 id 는 거부된다`() {
    // slug 규칙은 TenantId VO 가 강제한다 — Keycloak group 경로가 되므로 형식이 곧 계약이다.
    assertThatThrownBy { createTenantService.create(command("Acme Corp")) }
      .isInstanceOf(IllegalArgumentException::class.java)

    assertThat(tenantRepository.findAll()).isEmpty()
  }

  @Test
  fun `활성 멤버십은 중복될 수 없지만 탈퇴 이력은 남는다`() {
    // ⚠️ 부분 unique 인덱스(uq_membership_active)의 실측이다.
    // 단순 UNIQUE 였다면 탈퇴한 사용자가 **영영 재가입할 수 없다.**
    createTenantService.create(command("acme"))
    val existing = membershipRepository.findAll().single()

    existing.status = MembershipStatus.REVOKED
    membershipRepository.save(existing)

    // 탈퇴 이력이 있어도 같은 사용자가 다시 들어올 수 있다.
    val rejoined =
      me.ramos.unigate.iam.domain.membership.model.Membership.joinDirectly(
        userRef =
          me.ramos.unigate.iam.domain.shared.vo
            .UserRef(CREATOR),
        tenantId = TenantId("acme"),
        role =
          me.ramos.unigate.iam.domain.membership.vo.TenantRole
            .member(),
        at = java.time.Instant.now(),
      )
    tenantRepositoryPort.saveMembership(rejoined)

    assertThat(membershipRepository.findAll()).hasSize(2)
    assertThat(tenantRepositoryPort.countActiveMembers(TenantId("acme"))).isEqualTo(1)
  }

  private fun command(tenantId: String) =
    CreateTenantCommand(
      tenantId = tenantId,
      displayName = "테스트 테넌트",
      creatorRef = CREATOR,
    )

  private companion object {
    const val CREATOR = "99999999-8888-7777-6666-555555555555"
  }
}
