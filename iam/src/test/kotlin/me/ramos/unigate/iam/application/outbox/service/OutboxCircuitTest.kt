package me.ramos.unigate.iam.application.outbox.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * outbox 회로 차단기 단위 테스트 (Phase 9b).
 *
 * ## 무엇을 지키는 테스트인가
 * 이 차단기가 없으면 [me.ramos.unigate.iam.application.outbox.model.OutboxRecord.MAX_ATTEMPTS] 를
 * 5 로 줄인 결정이 **위험해진다** — 2분 남짓한 외부 장애에도 정상 가입이 DEAD 로 떨어진다.
 * 즉 여기서 검증하는 것은 "차단기가 동작한다" 가 아니라 **"재시도 상한 축소가 안전하다"** 는 전제다.
 *
 * 시간에 의존하는 로직이라 [MutableClock] 으로 시계를 직접 옮긴다. `Thread.sleep` 을 쓰면
 * 테스트가 느려지고 CI 부하에 따라 흔들린다.
 */
class OutboxCircuitTest :
  BehaviorSpec({
    val start = Instant.parse("2026-07-27T00:00:00Z")

    given("갓 만든 회로") {
      val clock = MutableClock(start)
      val circuit = OutboxCircuit(clock)

      `when`("아무 일도 없었으면") {
        then("닫혀 있어 시도할 수 있다") {
          circuit.canAttempt() shouldBe true
          circuit.isOpen() shouldBe false
        }
      }
    }

    given("연속 실패가 쌓이는 회로") {
      `when`("한계 직전까지 실패하면") {
        val circuit = OutboxCircuit(MutableClock(start))
        repeat(OutboxCircuit.FAILURE_THRESHOLD - 1) { circuit.recordFailure() }

        then("아직 닫혀 있다 — 한두 번의 실패로 멈추면 안 된다") {
          circuit.canAttempt() shouldBe true
        }
      }

      `when`("한계에 도달하면") {
        val circuit = OutboxCircuit(MutableClock(start))
        repeat(OutboxCircuit.FAILURE_THRESHOLD) { circuit.recordFailure() }

        then("회로가 열려 더 시도하지 않는다") {
          circuit.isOpen() shouldBe true
          circuit.canAttempt() shouldBe false
        }
      }

      `when`("중간에 성공이 끼면") {
        val circuit = OutboxCircuit(MutableClock(start))
        repeat(OutboxCircuit.FAILURE_THRESHOLD - 1) { circuit.recordFailure() }
        circuit.recordSuccess()
        circuit.recordFailure()

        then("카운터가 초기화되어 열리지 않는다") {
          // **연속** 실패만 센다. 산발적 실패로 회로가 열리면 정상 처리까지 멈춘다.
          circuit.canAttempt() shouldBe true
        }
      }
    }

    given("열린 회로") {
      `when`("열림 시간이 지나기 전이면") {
        val clock = MutableClock(start)
        val circuit = OutboxCircuit(clock)
        repeat(OutboxCircuit.FAILURE_THRESHOLD) { circuit.recordFailure() }
        clock.advance(Duration.ofSeconds(29))

        then("여전히 막는다") {
          circuit.canAttempt() shouldBe false
        }
      }

      `when`("열림 시간이 지나면") {
        val clock = MutableClock(start)
        val circuit = OutboxCircuit(clock)
        repeat(OutboxCircuit.FAILURE_THRESHOLD) { circuit.recordFailure() }
        clock.advance(Duration.ofSeconds(31))

        then("탐색 한 건을 흘려보낸다 (half-open)") {
          // 복구 여부를 알려면 실제로 한 번 시도해봐야 한다. 그렇다고 전부 흘려보내면
          // 아직 죽은 외부 시스템에 부하가 몰린다.
          circuit.canAttempt() shouldBe true
        }
      }

      `when`("탐색이 성공하면") {
        val clock = MutableClock(start)
        val circuit = OutboxCircuit(clock)
        repeat(OutboxCircuit.FAILURE_THRESHOLD) { circuit.recordFailure() }
        clock.advance(Duration.ofSeconds(31))
        circuit.canAttempt()
        circuit.recordSuccess()

        then("회로가 닫혀 정상으로 돌아온다") {
          circuit.isOpen() shouldBe false
          circuit.canAttempt() shouldBe true
        }
      }

      `when`("탐색이 다시 실패하면") {
        val clock = MutableClock(start)
        val circuit = OutboxCircuit(clock)
        repeat(OutboxCircuit.FAILURE_THRESHOLD) { circuit.recordFailure() }
        clock.advance(Duration.ofSeconds(31))
        circuit.canAttempt()
        circuit.recordFailure()

        then("즉시 다시 열리고 열림 시간을 처음부터 센다") {
          // 카운터를 더 셀 이유가 없다 — 복구되지 않았다는 것이 이미 확인됐다.
          circuit.canAttempt() shouldBe false

          // 직전 열림 시각이 아니라 **탐색 실패 시각**부터 다시 센다.
          clock.advance(Duration.ofSeconds(29))
          circuit.canAttempt() shouldBe false
          clock.advance(Duration.ofSeconds(2))
          circuit.canAttempt() shouldBe true
        }
      }
    }
  })

/**
 * 테스트에서 시간을 앞으로 돌리기 위한 시계.
 *
 * `Clock.fixed` 는 고정이라 half-open 전이를 검증할 수 없고, 실제 시계를 쓰면 `Thread.sleep` 으로
 * 30초를 기다려야 한다.
 */
private class MutableClock(
  private var current: Instant,
) : Clock() {
  fun advance(duration: Duration) {
    current = current.plus(duration)
  }

  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId?): Clock = this

  override fun instant(): Instant = current
}
