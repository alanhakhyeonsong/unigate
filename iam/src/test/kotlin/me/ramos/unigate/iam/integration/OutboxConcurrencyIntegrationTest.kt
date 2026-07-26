package me.ramos.unigate.iam.integration

import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **다중 인스턴스 안전성 검증** — outbox 의 `FOR UPDATE SKIP LOCKED` 가 실제로 동작하는가.
 *
 * ## 실제 PostgreSQL 이 필요한 이유
 * `SKIP LOCKED` 는 **DB 가 제공하는 것**이라 mock 으로는 증명할 수 없다. H2 같은 인메모리 DB 는
 * 이를 지원하지 않거나 다르게 동작해서, 통과해도 운영 DB 에서의 보장이 되지 않는다.
 *
 * ## 왜 Testcontainers 가 아닌가
 * 처음엔 Testcontainers 로 작성했으나 이 환경에서 컨테이너를 띄우지 못했다 — Docker 29.x 와
 * Testcontainers 1.21.3(Spring Boot BOM 이 고정) 조합에서 docker-java 가 소켓에 `/info` 를 물으면
 * **HTTP 400** 을 받는다(같은 소켓에 curl 로 물으면 200). Boot BOM 이 버전을 고정해 단순 업그레이드로
 * 풀리지도 않았다.
 *
 * 목적은 "Testcontainers 를 쓰는 것" 이 아니라 **실제 PostgreSQL 에서 SKIP LOCKED 를 확인하는 것**이므로,
 * `docker compose` 로 이미 띄우는 로컬 PostgreSQL 에 **JDBC 로 직접** 붙는다. Docker 소켓 API 를
 * 쓰지 않으므로 위 문제를 우회하고, 검증 강도는 동일하다.
 *
 * ## 실행 전 준비
 * ```bash
 * docker compose up -d
 * docker exec unigate-postgres createdb -U testuser unigate_iam_test   # 최초 1회
 * ./gradlew :iam:integrationTest
 * ```
 *
 * 개발용 `unigate_iam` 이 아니라 **별도 DB** 를 쓴다 — 이 테스트는 테이블을 비우므로 개발 데이터를
 * 지우면 안 된다.
 *
 * 계층: L4 통합. `@Tag("testcontainers")` 는 "로컬 전용(외부 인프라 필요)" 이라는 뜻으로 유지한다
 * (`./gradlew build` 에서 제외된다).
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
    // Keycloak 은 이 테스트에서 호출되지 않는다(워커를 끄고 포트를 직접 부른다).
    "unigate.iam.keycloak.server-url=http://localhost:1",
  ],
)
class OutboxConcurrencyIntegrationTest {
  @Autowired
  private lateinit var outboxPort: OutboxPort

  @Autowired
  private lateinit var outboxRepository: OutboxRecordJpaRepository

  @Autowired
  private lateinit var userProfileRepository: UserProfileJpaRepository

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @BeforeEach
  fun cleanUp() {
    outboxRepository.deleteAll()
    userProfileRepository.deleteAll()
  }

  @Test
  fun `여러 워커가 동시에 집어도 같은 레코드를 두 번 집지 않는다`() {
    // 다중 인스턴스를 스레드로 흉내 낸다. 각 스레드가 별도 트랜잭션에서 claimNext 를 부른다.
    val recordCount = 8
    val workerCount = 4
    repeat(recordCount) { i -> enqueue("worker-test-$i@example.local") }

    val claimedIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val startGate = CountDownLatch(1)
    val done = CountDownLatch(workerCount)
    val executor = Executors.newFixedThreadPool(workerCount)

    repeat(workerCount) {
      executor.submit {
        try {
          startGate.await() // 최대한 동시에 출발시킨다 — 경합을 만들기 위해
          repeat(recordCount) {
            transactionTemplate.execute {
              outboxPort.claimNext(Instant.now())?.let { record ->
                claimedIds.add(record.id!!)
                // 처리한 것으로 표시해야 다음 폴링에서 다시 잡히지 않는다.
                outboxPort.update(record.completed())
              }
            }
          }
        } finally {
          done.countDown()
        }
      }
    }

    startGate.countDown()
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue()
    executor.shutdown()

    // 핵심 단언: 집힌 id 에 **중복이 없다.**
    assertThat(claimedIds).doesNotHaveDuplicates()
    // 그리고 하나도 빠뜨리지 않았다.
    assertThat(claimedIds).hasSize(recordCount)
    assertThat(outboxRepository.findAll().map { it.status }.distinct())
      .containsExactly(OutboxStatus.COMPLETED)
  }

  @Test
  fun `이미 잠긴 행은 건너뛰고 다음 행을 집는다`() {
    // SKIP LOCKED 의 정의 그대로를 확인한다. FOR UPDATE 만 있었다면 두 번째 클레임이 **대기**한다.
    enqueue("skip-a@example.local")
    enqueue("skip-b@example.local")

    val firstClaimed = AtomicInteger(-1)
    val secondClaimed = AtomicInteger(-1)
    val firstHolding = CountDownLatch(1)
    val secondDone = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)

    // 워커 1: 한 건을 집고 트랜잭션을 **열어둔 채** 대기 → 그 행은 잠겨 있다.
    executor.submit {
      transactionTemplate.execute {
        val claimed = outboxPort.claimNext(Instant.now())
        firstClaimed.set(claimed!!.id!!.toInt())
        firstHolding.countDown()
        // 워커 2가 시도할 시간을 준다.
        secondDone.await(20, TimeUnit.SECONDS)
      }
    }

    // 워커 2: 워커 1이 잡고 있는 동안 클레임을 시도한다.
    executor.submit {
      firstHolding.await(20, TimeUnit.SECONDS)
      transactionTemplate.execute {
        // SKIP LOCKED 덕분에 **대기하지 않고** 다른 행을 받는다.
        outboxPort.claimNext(Instant.now())?.let { secondClaimed.set(it.id!!.toInt()) }
      }
      secondDone.countDown()
    }

    assertThat(secondDone.await(30, TimeUnit.SECONDS)).isTrue()
    executor.shutdown()

    assertThat(firstClaimed.get()).isNotEqualTo(-1)
    // 두 번째 워커가 **다른** 행을 받았다. -1 이면 대기하다 못 받은 것(SKIP LOCKED 없음).
    assertThat(secondClaimed.get()).isNotEqualTo(-1)
    assertThat(secondClaimed.get()).isNotEqualTo(firstClaimed.get())
  }

  @Test
  fun `백오프로 미뤄진 레코드는 시간이 되기 전까지 집히지 않는다`() {
    val now = Instant.now()
    val record = enqueue("backoff@example.local")

    // 재시도 실패 처리 → nextAttemptAt 이 미래로 밀린다.
    transactionTemplate.execute {
      outboxPort.update(record.failedRetryable(now, "test"))
    }

    // 지금 시각으로는 집히지 않는다.
    transactionTemplate.execute {
      assertThat(outboxPort.claimNext(now)).isNull()
    }

    // 백오프가 지난 시점으로는 집힌다.
    val afterBackoff = now.plus(OutboxRecord.backoffFor(1)).plusSeconds(1)
    transactionTemplate.execute {
      assertThat(outboxPort.claimNext(afterBackoff)).isNotNull()
    }
  }

  private fun enqueue(email: String): OutboxRecord =
    transactionTemplate.execute {
      outboxPort.enqueue(
        OutboxRecord.pending(
          eventType = OutboxEventType.CREATE_KEYCLOAK_USER,
          payload = """{"email":"$email","firstName":"a","lastName":"b"}""",
          now = Instant.now(),
        ),
      )
    }!!
}
