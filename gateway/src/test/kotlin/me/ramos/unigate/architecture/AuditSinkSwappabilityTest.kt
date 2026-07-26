package me.ramos.unigate.architecture

import me.ramos.unigate.adapter.loggingOut.LoggingAuditLogAdapter
import me.ramos.unigate.adapter.r2dbcOut.R2dbcAuditLogAdapter
import me.ramos.unigate.application.audit.port.inbound.RecordAuditEventInPort
import me.ramos.unigate.application.audit.port.outbound.SaveAuditEventOutPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * **교체가능성 실증** — 포트가 진짜 경계인지 확인한다 (Phase 5).
 *
 * ## 이 테스트가 증명하려는 것
 * 구현이 하나뿐인 인터페이스는 추상화가 맞는지 증명되지 않은 상태다. 포트가 특정 구현의 모양을
 * 그대로 베낀 "새는 추상화"일 수 있기 때문이다.
 *
 * 그래서 `SaveAuditEventOutPort` 에 두 번째 구현(로깅)을 붙이고, **설정 한 줄만으로** 갈아끼워지는지
 * 확인한다. 핵심은 어느 쪽을 쓰든 `application`·`domain` 코드가 **한 줄도 바뀌지 않는다**는 것이다.
 *
 * ## 왜 빈 타입을 직접 확인하나
 * "교체된다"는 주장은 **실제 컨텍스트 조립 결과**로만 증명된다. 목(mock)으로는 증명이 되지 않는다.
 * 그래서 `@SpringBootTest` 로 진짜 조립을 두 번 시키고 주입된 구현체를 확인한다.
 *
 * 계층: L4(풀 컨텍스트). 외부 의존이 없어(test 프로파일) `@Tag("testcontainers")` 는 붙이지 않는다.
 */
class AuditSinkSwappabilityTest {
  /**
   * 기본값 — `unigate.audit.sink` 미설정.
   *
   * 감사는 조회·보존이 필요하므로 DB 가 기본이어야 한다. 설정을 빠뜨렸을 때 감사가 조용히
   * 로그로만 남는 상황을 막는 안전장치이기도 하다(`matchIfMissing = true`).
   */
  @Nested
  @DisplayName("unigate.audit.sink 를 설정하지 않으면")
  @SpringBootTest
  @ActiveProfiles("test")
  inner class DefaultSink {
    @Autowired
    private lateinit var outPort: SaveAuditEventOutPort

    @Test
    fun `R2DBC 어댑터가 주입된다`() {
      assertThat(outPort).isInstanceOf(R2dbcAuditLogAdapter::class.java)
    }
  }

  /**
   * `unigate.audit.sink=log` — 설정 한 줄로 구현이 바뀐다.
   *
   * 이 컨텍스트에는 `R2dbcAuditLogAdapter` 빈이 **아예 없다.** 그런데도 UseCase 는 정상 조립된다 —
   * UseCase 가 구현이 아니라 포트에만 의존하기 때문이다. 그게 이 테스트의 요점이다.
   */
  @Nested
  @DisplayName("unigate.audit.sink=log 로 바꾸면")
  @SpringBootTest(properties = ["unigate.audit.sink=log"])
  @ActiveProfiles("test")
  inner class LogSink {
    @Autowired
    private lateinit var outPort: SaveAuditEventOutPort

    @Autowired
    private lateinit var inPort: RecordAuditEventInPort

    @Test
    fun `로깅 어댑터로 교체된다`() {
      assertThat(outPort).isInstanceOf(LoggingAuditLogAdapter::class.java)
    }

    @Test
    fun `UseCase 는 구현이 바뀌어도 그대로 조립된다`() {
      // application 레이어가 어느 어댑터가 꽂혔는지 전혀 모른다는 것의 확인.
      // 이것이 실패하면 포트 경계가 샌 것이다(UseCase 가 특정 구현에 묶여 있다).
      assertThat(inPort).isNotNull()
    }
  }
}
