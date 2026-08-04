package me.ramos.billing

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
 * `TenantId` 규칙(`iam/.../TenantId.kt` — 소문자·숫자·하이픈)에 맞는 값을 쓴다. 실제 realm 에
 * 만들어 둔 테넌트 그룹 이름과 맞춰야 하며, 다르면 시나리오가 전부 403 으로만 끝나 무엇이 막혔는지
 * 구분되지 않는다(`docs/ALPHA_CONSOLE_SCENARIOS.md` 의 사전조건과 같은 성격).
 */
@Repository
class SubscriptionRepository {
    private val store =
        listOf(
            Subscription(id = "sub-a-1", tenantId = "acme", plan = "standard", monthlyFeeKrw = 99_000),
            Subscription(id = "sub-a-2", tenantId = "acme", plan = "enterprise", monthlyFeeKrw = 990_000),
            Subscription(id = "sub-b-1", tenantId = "globex", plan = "standard", monthlyFeeKrw = 99_000),
            Subscription(id = "sub-b-2", tenantId = "globex", plan = "trial", monthlyFeeKrw = 0),
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
