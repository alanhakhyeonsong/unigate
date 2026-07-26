package me.ramos.unigate.iam.application.outbox.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * outbox 레코드의 **재시도 정책** 단위 테스트.
 *
 * 백오프와 한도는 순수 계산이라 DB 없이 검증할 수 있다. 반대로 SKIP LOCKED 동시성은 실제
 * PostgreSQL 이 필요하다(`OutboxConcurrencyIntegrationTest`) — 무엇을 어디서 검증할지가 갈린다.
 */
class OutboxRecordTest :
  BehaviorSpec({
    val now = Instant.parse("2026-07-26T00:00:00Z")

    fun newRecord() =
      OutboxRecord.pending(
        eventType = OutboxEventType.CREATE_KEYCLOAK_USER,
        payload = """{"email":"a@b.c"}""",
        now = now,
      )

    given("새로 만든 지시") {
      `when`("상태를 보면") {
        val record = newRecord()

        then("PENDING 이고 즉시 처리 대상이다") {
          record.status shouldBe OutboxStatus.PENDING
          record.attempts shouldBe 0
          record.nextAttemptAt shouldBe now
        }
      }
    }

    given("재시도 가능한 실패") {
      `when`("한 번 실패하면") {
        val record = newRecord().failedRetryable(now, "identity_provider_unavailable")

        then("PENDING 을 유지하고 백오프만큼 뒤로 밀린다") {
          record.status shouldBe OutboxStatus.PENDING
          record.attempts shouldBe 1
          record.nextAttemptAt shouldBe now.plus(Duration.ofSeconds(10))
        }
      }

      `when`("연속으로 실패하면") {
        then("백오프가 지수적으로 늘어난다 — 외부 시스템을 계속 두드리지 않는다") {
          OutboxRecord.backoffFor(1) shouldBe Duration.ofSeconds(10)
          OutboxRecord.backoffFor(2) shouldBe Duration.ofSeconds(20)
          OutboxRecord.backoffFor(3) shouldBe Duration.ofSeconds(40)
        }

        then("상한(5분)을 넘지 않는다 — 복구된 뒤 한참 기다리는 것을 막는다") {
          OutboxRecord.backoffFor(20) shouldBe Duration.ofMinutes(5)
        }
      }

      `when`("최대 시도 횟수에 도달하면") {
        // MAX_ATTEMPTS 직전까지 실패시킨다.
        var record = newRecord()
        repeat(OutboxRecord.MAX_ATTEMPTS - 1) {
          record = record.failedRetryable(now, "boom")
        }

        then("아직은 PENDING 이다") {
          record.status shouldBe OutboxStatus.PENDING
          record.attempts shouldBe OutboxRecord.MAX_ATTEMPTS - 1
        }

        then("한 번 더 실패하면 DEAD 가 된다 — 무한 재시도는 문제를 감춘다") {
          val dead = record.failedRetryable(now, "boom")
          dead.status shouldBe OutboxStatus.DEAD
          dead.attempts shouldBe OutboxRecord.MAX_ATTEMPTS
        }
      }
    }

    given("재시도해도 소용없는 실패 (이메일 중복 등)") {
      `when`("영구 실패로 처리하면") {
        val record = newRecord().failedPermanently("identity_already_exists")

        then("시도 횟수와 무관하게 즉시 DEAD 다") {
          record.status shouldBe OutboxStatus.DEAD
          record.attempts shouldBe 1
        }

        then("사유가 남아 운영자가 원인을 안다") {
          record.lastError shouldBe "identity_already_exists"
        }
      }
    }

    given("성공") {
      `when`("완료 처리하면") {
        val record = newRecord().failedRetryable(now, "일시 실패").completed()

        then("COMPLETED 가 되고 이전 오류가 지워진다") {
          record.status shouldBe OutboxStatus.COMPLETED
          record.lastError shouldBe null
        }
      }
    }
  })
