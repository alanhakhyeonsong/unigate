package me.ramos.downstream

import org.springframework.stereotype.Repository

/**
 * Phase 9g 후속 — **자원 격리를 잊을 수 없게 만든다.**
 *
 * ## 검사를 잘 하는 것과, 검사가 필요 없게 만드는 것
 * 컨트롤러에서 `order.tenantId == tenant.tenantId` 를 비교하는 방식은 **비교를 잊으면 남의
 * 데이터가 나온다.** 그래서 비교를 없애고 **질의 자체에 테넌트를 강제**한다.
 *
 * 이 저장소에는 `findById(id)` 가 **없다.** 테넌트 없이 조회하는 함수를 제공하지 않으므로,
 * 안전하지 않은 질의는 실수로 쓰이는 게 아니라 **애초에 표현할 수 없다.** 잊었을 때의 결과가
 * "남의 데이터"가 아니라 **컴파일 에러**가 된다.
 *
 * > `docs/learning/20` 의 판단과 같다 — 대상을 토큰 `sub` 로만 정하면 IDOR 이 성립할 자리가
 * > 없다. 여기서는 대상을 **테넌트로만** 정한다.
 *
 * ## 실제 DB 라면
 * JPA 라면 `@Filter`(Hibernate) 나 base repository 로 `WHERE tenant_id = ?` 를 강제하고,
 * 더 강하게 가려면 PostgreSQL Row-Level Security 로 **DB 가** 강제한다. 어느 쪽이든 원칙은
 * 같다 — 테넌트 조건을 **개발자가 기억해야 하는 것**에서 **구조가 보장하는 것**으로 옮긴다.
 */
@Repository
class OrderRepository {
    /** 실제 서비스라면 테이블이다. 자원마다 소유 테넌트가 붙어 있다는 것이 요점이다. */
    private val orders =
        mutableListOf(
            Order("acme-1", "acme", "노트북 거치대"),
            Order("acme-2", "acme", "기계식 키보드"),
            Order("globex-1", "globex", "커피머신"),
        )

    private var sequence = 100

    /**
     * 테넌트 범위 안에서만 찾는다.
     *
     * 남의 테넌트 자원을 요청하면 `null` 이다 — "권한 없음"이 아니라 **"없음"**. 403 을 주면
     * "그 id 는 존재한다"를 알려주는 셈이라, 없는 것과 구분되지 않게 한다.
     */
    fun findById(
        tenant: TenantContext,
        id: String,
    ): Order? = orders.firstOrNull { it.tenantId == tenant.tenantId && it.id == id }

    fun findAll(tenant: TenantContext): List<Order> = orders.filter { it.tenantId == tenant.tenantId }

    /**
     * 생성 — **소유 테넌트를 호출자가 정하지 못한다.**
     *
     * `tenantId` 를 파라미터로 받지 않고 [TenantContext] 에서만 꺼낸다. 요청 본문이 무엇을
     * 말하든 여기까지 오지 못하므로, "본문의 테넌트와 헤더의 테넌트가 다르면?" 이라는 질문
     * 자체가 성립하지 않는다.
     *
     * ⚠️ 읽기의 `findById(tenant, id)` 와 성질이 다르다. 읽기는 **범위를 좁히는** 것이고
     * 쓰기는 **소유자를 정하는** 것이다. 쓰기에서 테넌트를 파라미터로 열어두면 범위 검사가
     * 아무리 촘촘해도 남의 테넌트에 자원을 만들 수 있다.
     */
    fun create(
        tenant: TenantContext,
        item: String,
    ): Order =
        Order(
            id = "${tenant.tenantId}-${sequence++}",
            tenantId = tenant.tenantId,
            item = item,
        ).also { orders.add(it) }

    /** 수정 — 범위 밖이면 `null`. 남의 자원은 "권한 없음" 이 아니라 **"없음"** 이다. */
    fun updateItem(
        tenant: TenantContext,
        id: String,
        item: String,
    ): Order? {
        val index = orders.indexOfFirst { it.tenantId == tenant.tenantId && it.id == id }
        if (index < 0) return null
        val updated = orders[index].copy(item = item)
        orders[index] = updated
        return updated
    }

    /**
     * ⚠️ **일부러 위험하게 만든 함수다. 증명 수단이며 실제 코드가 아니다.**
     *
     * 소유 테넌트를 파라미터로 받는다 — 즉 호출자가 남의 테넌트에 자원을 만들 수 있다.
     * `LegacyOrderController` 가 이걸 써서 "쓰기 경로는 default-deny 로 막히지 않는다" 를 보인다.
     */
    fun unsafeCreateWithExplicitTenant(
        tenantId: String,
        item: String,
    ): Order =
        Order(
            id = "$tenantId-${sequence++}",
            tenantId = tenantId,
            item = item,
        ).also { orders.add(it) }
}

data class Order(
    val id: String,
    val tenantId: String,
    val item: String,
)
