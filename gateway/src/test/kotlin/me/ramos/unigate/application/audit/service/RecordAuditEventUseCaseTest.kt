package me.ramos.unigate.application.audit.service

import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import me.ramos.unigate.application.audit.dto.RecordAuditEventCommand
import me.ramos.unigate.application.audit.port.outbound.SaveAuditEventOutPort
import me.ramos.unigate.domain.audit.enums.AuditEventType
import me.ramos.unigate.domain.audit.model.AuditEvent

/**
 * L1 단위 — UseCase 가 Command 를 도메인 이벤트로 매핑해 OutPort 로 위임하는지만 본다.
 * OutPort(SaveAuditEventOutPort)만 모킹한다(testing skill 규칙).
 */
class RecordAuditEventUseCaseTest :
  BehaviorSpec({
    val saveAuditEventOutPort = mockk<SaveAuditEventOutPort>()
    val useCase = RecordAuditEventUseCase(saveAuditEventOutPort)

    given("로그인 성공 감사 커맨드") {
      val command =
        RecordAuditEventCommand(
          type = AuditEventType.LOGIN_SUCCESS,
          subject = "alice-sub",
          clientId = "keycloak",
          detail = mapOf("preferredUsername" to "alice"),
        )
      coEvery { saveAuditEventOutPort.save(any()) } returns Unit

      `when`("record 하면") {
        useCase.record(command)

        then("OutPort 에 매핑된 도메인 이벤트가 그대로 저장된다") {
          coVerify(exactly = 1) {
            saveAuditEventOutPort.save(
              AuditEvent(
                type = AuditEventType.LOGIN_SUCCESS,
                subject = "alice-sub",
                clientId = "keycloak",
                detail = mapOf("preferredUsername" to "alice"),
              ),
            )
          }
        }
      }
    }
  })
