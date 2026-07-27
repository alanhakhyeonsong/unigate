package me.ramos.unigate.iam.adapter.iamIn

import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.service.OutboxAdminService
import me.ramos.unigate.iam.application.outbox.service.OutboxNotDeadException
import me.ramos.unigate.iam.application.outbox.service.OutboxRecordNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant

/**
 * DLQ 운영 API — 죽은 outbox 지시의 조회·재처리 (Phase 9c).
 *
 * ## 인가가 **여기서 처음으로 명시적**이다
 * 프로필 API([ProfileController])는 대상을 토큰 `sub` 로만 정해 인가 검사 코드가 아예 없었다
 * (`docs/learning/20` — IDOR 이 성립할 자리를 없앤 설계). 이 컨트롤러는 **남의 자원**을 다루므로
 * 그 전략이 통하지 않는다.
 *
 * 검사는 `IamSecurityConfig` 가 경로 규칙(`/iam/admin` 하위 전체 → `unigate-admin`)으로 한다.
 * 메서드마다 `@PreAuthorize` 를 붙이지 않는 이유: **새 메서드를 추가하고 애노테이션을 잊으면
 * 인증만으로 뚫린다.** 경로 접두사로 막으면 잊어도 안전한 쪽으로 실패한다.
 *
 * ## 이 API 가 하지 않는 것
 * 재처리를 **직접 수행하지 않는다.** 상태만 되돌리고 워커가 정상 클레임 경로로 가져간다.
 * 여기서 Keycloak 을 직접 부르면 워커와 두 벌이 되어 한쪽만 고치는 사고가 난다.
 */
@RestController
@RequestMapping("/iam/admin/outbox")
class OutboxAdminController(
  private val outboxAdminService: OutboxAdminService,
) {
  /**
   * 죽은 레코드 목록. 최근에 죽은 것부터.
   *
   * `payload` 는 응답에 담지 않는다 — 가입 payload 에는 이메일·이름이 들어 있고, 운영 조회는
   * "무엇이 왜 죽었나" 를 보는 것이지 사용자 데이터를 열람하는 자리가 아니다.
   */
  @GetMapping("/dead")
  fun listDead(
    @RequestParam(defaultValue = "50") limit: Int,
  ): DeadOutboxListResponse =
    DeadOutboxListResponse(
      items = outboxAdminService.listDead(limit).map { it.toSummary() },
    )

  /**
   * 죽은 레코드를 다시 처리 대상으로 되돌린다.
   *
   * 응답은 202 가 아니라 **200** 이다. "요청을 접수했다" 가 아니라 **"상태를 되돌렸다"** 라는
   * 완료된 사실을 돌려주기 때문이다. 실제 외부 반영이 비동기인 것은 그다음 이야기이고,
   * 그건 원래 outbox 의 성질이다.
   */
  @PostMapping("/dead/{id}/requeue")
  fun requeue(
    @PathVariable id: Long,
    authentication: JwtAuthenticationToken,
  ): DeadOutboxSummary = outboxAdminService.requeue(id, authentication.token.subject).toSummary()

  @ExceptionHandler(OutboxRecordNotFoundException::class)
  fun handleNotFound(e: OutboxRecordNotFoundException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "대상 outbox 레코드를 찾을 수 없습니다").apply {
      type = URI.create("urn:unigate:iam:outbox-record-not-found")
      title = "Outbox Record Not Found"
      setProperty("reasonCode", "outbox_record_not_found")
      setProperty("outboxRecordId", e.id)
    }

  /**
   * 409 다 — 요청 자체는 올바르나 **대상의 현재 상태와 충돌**한다.
   *
   * 400 이 아닌 이유: id 도 형식도 정상이다. 404 도 아니다: 레코드는 존재한다.
   * 이 구분이 있어야 호출자가 "다시 조회해 보라" 를 알 수 있다.
   */
  @ExceptionHandler(OutboxNotDeadException::class)
  fun handleNotDead(e: OutboxNotDeadException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "DEAD 상태가 아닌 레코드는 재처리할 수 없습니다").apply {
      type = URI.create("urn:unigate:iam:outbox-not-dead")
      title = "Outbox Record Not Dead"
      setProperty("reasonCode", "outbox_not_dead")
      setProperty("outboxRecordId", e.id)
      setProperty("currentStatus", e.status.name)
    }

  private fun OutboxRecord.toSummary() =
    DeadOutboxSummary(
      id = requireNotNull(id) { "저장된 레코드는 id 를 갖는다" },
      eventType = eventType.name,
      status = status.name,
      attempts = attempts,
      lastError = lastError,
      lastExceptionClass = lastExceptionClass,
      deadAt = deadAt,
      nextAttemptAt = nextAttemptAt,
    )
}

data class DeadOutboxListResponse(
  val items: List<DeadOutboxSummary>,
)

/**
 * 운영 조회용 요약.
 *
 * ⚠️ `payload` 가 없다 — 개인정보가 들어 있고 운영 판단에는 필요 없다(`CLAUDE.md` §8).
 * 어떤 사용자의 건인지 확인해야 한다면 감사 로그의 `target_email` 로 찾는다.
 */
data class DeadOutboxSummary(
  val id: Long,
  val eventType: String,
  val status: String,
  val attempts: Int,
  val lastError: String?,
  val lastExceptionClass: String?,
  val deadAt: Instant?,
  val nextAttemptAt: Instant,
)
