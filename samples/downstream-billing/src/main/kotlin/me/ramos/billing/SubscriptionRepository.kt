package me.ramos.billing

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository

/**
 * 구독(청구) 자원. **자원이 자기 테넌트를 안다** — 이게 §5.5.1 의 핵심이다.
 *
 * `paas-iam-scope-review.md` §5.5.1 표: 자원 속성은 **제품 BE 의 DB 에만** 있고 개수가 무계라
 * 중앙으로 옮길 수 없다. 그래서 판정이 자원 쪽으로 온다. 이 클래스가 그 "옮길 수 없는 속성" 이다.
 */
data class Subscription(
    val id: String,
    val tenantId: String,
    val plan: String,
    val monthlyFeeKrw: Long,
)

/**
 * 인메모리 픽스처. **두 테넌트의 자원이 섞여 있어야** 교차 테넌트 판정을 재현할 수 있다.
 *
 * ## ⚠️ 테넌트 ID 는 **realm 에 실재하는 값**이어야 한다 (겪은 함정)
 * 픽스처의 테넌트가 realm 에 없으면 **아무도 그 테넌트에 속하지 않으므로**, 취약 엔드포인트
 * (`SubscriptionController`)마저 `subscription.tenantId !in memberships` 에서 403 을 낸다.
 *
 * 그러면 **시나리오가 통과한 것처럼 보인다.** 구멍이 막혀서가 아니라 **재현 조건이 성립하지
 * 않아서**인데, 응답만 보면 구분되지 않는다 — 검증이 거짓 안심을 준다.
 *
 * 실제로 **환경마다 테넌트 이름이 달랐다** — 로컬 realm 과 alpha realm 이 서로 다른 값을 쓴다.
 * 그래서 하드코딩하지 않고 주입받는다. 기본값은 로컬 realm 기준이고, 다른 환경에서는
 * `BILLING_FIXTURE_TENANT_A` · `_B` 로 그 realm 에 실재하는 값을 넣는다.
 *
 * ## 재현에 필요한 조건 (둘 다 필요)
 * 1. 두 테넌트가 realm 에 **실재**한다
 * 2. 시나리오를 도는 사용자가 **양쪽 모두에 소속**돼 있다 — 한쪽만이면 우연히 막혀 위와 같은
 *    거짓 통과가 된다
 */
@Repository
class SubscriptionRepository(
    @param:Value("\${unigate.billing.fixture.tenant-a}") private val tenantA: String,
    @param:Value("\${unigate.billing.fixture.tenant-b}") private val tenantB: String,
) {
    /**
     * id 접두사(`sub-a-` / `sub-b-`)는 **테넌트 이름과 무관하게 고정**한다.
     * 시나리오 문서와 재연 스크립트가 이 id 를 그대로 쓰므로, 환경마다 id 가 달라지면
     * 같은 문서로 두 환경을 검증할 수 없다. 바뀌는 것은 `tenantId` 뿐이다.
     */
    private val store: Map<String, Subscription> =
        listOf(
            Subscription(id = "sub-a-1", tenantId = tenantA, plan = "standard", monthlyFeeKrw = 99_000),
            Subscription(id = "sub-a-2", tenantId = tenantA, plan = "enterprise", monthlyFeeKrw = 990_000),
            Subscription(id = "sub-b-1", tenantId = tenantB, plan = "standard", monthlyFeeKrw = 99_000),
            Subscription(id = "sub-b-2", tenantId = tenantB, plan = "trial", monthlyFeeKrw = 0),
        ).associateBy { it.id }

    /**
     * **테넌트를 묻지 않고** id 로만 찾는다.
     *
     * ⚠️ 의도적이다. `docs/learning/24` 가 다룬 demo 의 `OrderRepository` 는 반대로 테넌트를
     * 필수 인자로 받아 "잊으면 닫히게" 만들었다. 여기서는 그 안전장치를 빼서 **판정 책임이
     * 호출자에게 있는 상태**를 만든다 — 그래야 호출자가 틀렸을 때 무슨 일이 생기는지 보인다.
     */
    fun findById(id: String): Subscription? = store[id]

    /** 특정 테넌트의 구독만. 이쪽은 자원 접근 자체가 테넌트로 좁혀진다. */
    fun findByTenant(tenantId: String): List<Subscription> = store.values.filter { it.tenantId == tenantId }
}
