package me.ramos.unigate.iam.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import me.ramos.unigate.iam.adapter.jpaOut.repository.AuditLogJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.MembershipJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.TenantJpaRepository
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.application.tenant.service.ChangeRoleCommand
import me.ramos.unigate.iam.application.tenant.service.CreateTenantCommand
import me.ramos.unigate.iam.application.tenant.service.CreateTenantService
import me.ramos.unigate.iam.application.tenant.service.InviteMemberCommand
import me.ramos.unigate.iam.application.tenant.service.MembershipAlreadyExistsException
import me.ramos.unigate.iam.application.tenant.service.MembershipNotFoundException
import me.ramos.unigate.iam.application.tenant.service.MembershipService
import me.ramos.unigate.iam.application.tenant.service.RevokeMembershipCommand
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import me.ramos.unigate.iam.domain.tenant.exception.TenantDomainException
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
 * 멤버십 수명주기 통합 테스트 — 실제 PostgreSQL (Phase 9d).
 *
 * ## 여기서만 확인되는 것
 * 1. **쿼터가 수락 시점에** 걸린다 — 초대는 자리를 차지하지 않는다는 규칙의 실측
 * 2. Keycloak 투영이 **수락·해제에만** 발행된다 — 초대·역할변경에는 지시가 생기지 않는다
 * 3. 초대 취소(INVITED→REVOKED)에는 **group 제거 지시가 없다** — 넣은 적이 없으므로
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
class MembershipFlowIntegrationTest {
  @Autowired
  private lateinit var createTenantService: CreateTenantService

  @Autowired
  private lateinit var membershipService: MembershipService

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
    every { identityProviderPort.createTenantGroup(any()) } returns Unit
    every { identityProviderPort.addUserToTenantGroup(any(), any()) } returns Unit
    every { identityProviderPort.removeUserFromTenantGroup(any(), any()) } returns Unit
  }

  @Test
  fun `초대는 쿼터를 차지하지 않고 group 투영도 없다`() {
    activeTenant("acme", maxUsers = 2)
    outboxRepository.deleteAll()

    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))

    val membership = membershipRepository.findAll().single { it.userRef == MEMBER_A }
    assertThat(membership.status).isEqualTo(MembershipStatus.INVITED)
    assertThat(membership.joinedAt).isNull()

    // 생성자 1명만 계상된다 — 초대는 아직 자리를 차지하지 않는다.
    assertThat(tenantRepositoryPort.countActiveMembers(TenantId("acme"))).isEqualTo(1)

    // ⚠️ INVITED 는 아직 멤버가 아니므로 **claim 에 실려서는 안 된다** → 투영 지시가 없어야 한다.
    assertThat(outboxRepository.findAll()).isEmpty()
  }

  @Test
  fun `수락하면 활성 멤버가 되고 group 에 반영된다`() {
    activeTenant("acme", maxUsers = 3)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))
    outboxRepository.deleteAll()

    membershipService.accept("acme", MEMBER_A)

    val membership = membershipRepository.findAll().single { it.userRef == MEMBER_A }
    assertThat(membership.status).isEqualTo(MembershipStatus.ACTIVE)
    assertThat(membership.joinedAt).isNotNull()
    assertThat(tenantRepositoryPort.countActiveMembers(TenantId("acme"))).isEqualTo(2)

    val instruction = outboxRepository.findAll().single()
    assertThat(instruction.eventType).isEqualTo(OutboxEventType.ADD_GROUP_MEMBER)

    assertThat(outboxProcessor.processOne()).isTrue()
    verify { identityProviderPort.addUserToTenantGroup("acme", MEMBER_A) }
  }

  @Test
  fun `정원이 차면 초대는 되지만 수락이 거부된다`() {
    // ⚠️ P9d 의 핵심 설계 판단이다.
    //
    // 쿼터를 초대 시점에 검사하면, 정원만큼 초대해두고 아무도 수락하지 않은 상태에서
    // **실제 멤버는 적은데 더 못 부르는** 상황이 된다. 수락 시점에 보면 초대는 얼마든지 하되
    // **먼저 수락한 사람이 자리를 갖는다.**
    //
    // 그 대가가 이것 — 초대받고도 못 들어올 수 있다. 결함이 아니라 선택이다.
    activeTenant("acme", maxUsers = 1) // 생성자 1명으로 이미 정원

    // 초대 자체는 통과한다.
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))

    assertThatThrownBy { membershipService.accept("acme", MEMBER_A) }
      .isInstanceOf(TenantDomainException.QuotaExceeded::class.java)

    // 상태가 바뀌지 않았고 투영 지시도 생기지 않았다.
    assertThat(membershipRepository.findAll().single { it.userRef == MEMBER_A }.status)
      .isEqualTo(MembershipStatus.INVITED)
    assertThat(outboxRepository.findAll().none { it.eventType == OutboxEventType.ADD_GROUP_MEMBER }).isTrue()
  }

  @Test
  fun `역할을 바꿔도 group 투영은 없다`() {
    // P9d 의 Keycloak 투영은 **group 소속까지**다. 역할은 IAM DB 가 SoT 이고,
    // 토큰 claim 으로 내보내는 것은 P9e 의 몫이다.
    activeTenant("acme", maxUsers = 5)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))
    membershipService.accept("acme", MEMBER_A)
    outboxRepository.deleteAll()
    auditLogRepository.deleteAll()

    membershipService.changeRole(ChangeRoleCommand("acme", MEMBER_A, "tenant-admin", ADMIN))

    assertThat(membershipRepository.findAll().single { it.userRef == MEMBER_A }.role)
      .isEqualTo("tenant-admin")
    assertThat(outboxRepository.findAll()).isEmpty()

    // 권한 변화는 "무엇이 되었나" 만으로는 감사가 안 된다 — 전후를 함께 남긴다.
    val event = auditLogRepository.findAll().single()
    assertThat(event.eventType).isEqualTo(AuditEventType.MEMBERSHIP_ROLE_CHANGED)
    assertThat(event.detail).contains("member").contains("tenant-admin")
  }

  @Test
  fun `활성 멤버를 해제하면 group 에서도 빠진다`() {
    activeTenant("acme", maxUsers = 5)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))
    membershipService.accept("acme", MEMBER_A)
    outboxRepository.deleteAll()

    membershipService.revoke(RevokeMembershipCommand("acme", MEMBER_A, ADMIN))

    assertThat(membershipRepository.findAll().single { it.userRef == MEMBER_A }.status)
      .isEqualTo(MembershipStatus.REVOKED)
    // 자리가 반납된다.
    assertThat(tenantRepositoryPort.countActiveMembers(TenantId("acme"))).isEqualTo(1)

    val instruction = outboxRepository.findAll().single()
    assertThat(instruction.eventType).isEqualTo(OutboxEventType.REMOVE_GROUP_MEMBER)
    assertThat(outboxProcessor.processOne()).isTrue()
    verify { identityProviderPort.removeUserFromTenantGroup("acme", MEMBER_A) }
  }

  @Test
  fun `초대만 취소하면 group 제거 지시가 생기지 않는다`() {
    // 넣은 적이 없으므로 뺄 것도 없다. 불필요한 지시를 만들면 워커가 "없는 것을 지우는"
    // 호출을 반복한다(어댑터가 404 를 성공으로 처리하니 조용히 낭비된다).
    activeTenant("acme", maxUsers = 5)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))
    outboxRepository.deleteAll()

    membershipService.revoke(RevokeMembershipCommand("acme", MEMBER_A, ADMIN))

    assertThat(membershipRepository.findAll().single { it.userRef == MEMBER_A }.status)
      .isEqualTo(MembershipStatus.REVOKED)
    assertThat(outboxRepository.findAll()).isEmpty()
  }

  @Test
  fun `해제한 사용자를 다시 초대할 수 있다`() {
    // 부분 unique 인덱스(uq_membership_active)가 이걸 가능하게 한다.
    // 단순 UNIQUE 였다면 탈퇴 이력이 영영 재초대를 막는다.
    activeTenant("acme", maxUsers = 5)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))
    membershipService.revoke(RevokeMembershipCommand("acme", MEMBER_A, ADMIN))

    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))

    val all = membershipRepository.findAll().filter { it.userRef == MEMBER_A }
    assertThat(all).hasSize(2)
    assertThat(all.map { it.status })
      .containsExactlyInAnyOrder(MembershipStatus.REVOKED, MembershipStatus.INVITED)
  }

  @Test
  fun `이미 유효한 멤버십이 있으면 중복 초대할 수 없다`() {
    activeTenant("acme", maxUsers = 5)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))

    assertThatThrownBy { membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN)) }
      .isInstanceOf(MembershipAlreadyExistsException::class.java)
  }

  @Test
  fun `초대받지 않은 사람은 수락할 수 없다`() {
    activeTenant("acme", maxUsers = 5)

    assertThatThrownBy { membershipService.accept("acme", MEMBER_A) }
      .isInstanceOf(MembershipNotFoundException::class.java)
  }

  @Test
  fun `초대 수락은 감사에서 actor 와 target 이 같다`() {
    // 관리 사건 중 유일하게 본인이 자기 것에 하는 행위다 — 그래서 관리 경로에 두지 않았다.
    activeTenant("acme", maxUsers = 5)
    membershipService.invite(InviteMemberCommand("acme", MEMBER_A, "member", ADMIN))
    auditLogRepository.deleteAll()

    membershipService.accept("acme", MEMBER_A)

    val event = auditLogRepository.findAll().single()
    assertThat(event.eventType).isEqualTo(AuditEventType.MEMBERSHIP_ACCEPTED)
    assertThat(event.actorRef).isEqualTo(MEMBER_A)
    assertThat(event.targetRef).isEqualTo(MEMBER_A)
    assertThat(event.tenantRef).isEqualTo("acme")
  }

  /** 테넌트를 만들고 워커까지 돌려 ACTIVE 로 만든다(멤버를 받을 수 있는 상태). */
  private fun activeTenant(
    id: String,
    maxUsers: Int,
  ) {
    createTenantService.create(
      CreateTenantCommand(tenantId = id, displayName = "테스트", creatorRef = ADMIN, maxUsers = maxUsers),
    )
    outboxProcessor.processOne()
  }

  private companion object {
    const val ADMIN = "99999999-8888-7777-6666-555555555555"
    const val MEMBER_A = "11111111-2222-3333-4444-555555555555"
  }
}
