---
name: domain-model
description: 도메인 모델 규칙(Private constructor, Factory method, 불변성)과 Value Object(@JvmInline value class) 작성 규칙. 도메인 모델 생성/수정 시 참조한다.
---

# 도메인 모델 & Value Object 규칙

## 규칙 1: Private Constructor + Factory Method

도메인 모델은 반드시 private constructor를 사용하고, companion object에 factory method를 제공한다.

- `create()`: 새로운 엔티티 생성 (id 없음)
- `restore()`: DB에서 복원 (id 있음)

// WRONG - public constructor 직접 노출
```kotlin
class Alert(
    val id: AlertId? = null,
    val name: String,
    val price: BigDecimal,
)
```

// CORRECT - private constructor + factory
```kotlin
class Alert private constructor(
    val id: AlertId? = null,
    val name: String,
    val price: BigDecimal,
) {
    companion object {
        fun create(name: String, price: BigDecimal): Alert =
            Alert(name = name, price = price)

        fun restore(id: AlertId, name: String, price: BigDecimal): Alert =
            Alert(id = id, name = name, price = price)
    }
}
```

## 규칙 2: 불변성 우선

- `val` 사용이 기본. `var`는 도메인 로직에 의해 변경이 필요한 경우만 허용.
- 상태 변경은 도메인 메서드를 통해서만 수행.

### val만 사용하는 경우 — 새 객체 반환
모든 필드가 불변이면 상태 변경 시 새 객체를 생성하여 반환한다.

```kotlin
class Alert private constructor(
    val id: AlertId? = null,
    val status: AlertStatus = AlertStatus.ACTIVE,
) {
    fun trigger(): Alert = Alert(
        id = this.id,
        status = AlertStatus.TRIGGERED,
    )
    companion object { ... }
}
```

### var를 사용하는 경우 — private set + 도메인 메서드
변경이 필요한 필드는 constructor에 일반 파라미터로 받고, 클래스 body에 `var ... private set`으로 선언한다. 외부에서는 도메인 메서드를 통해서만 변경 가능하다.

```kotlin
class Member private constructor(
    val id: MemberId,
    val name: String? = null,
    giteaApiToken: String?,       // val/var 없이 일반 파라미터로 받음
    deleted: Boolean = false,     // val/var 없이 일반 파라미터로 받음
    var updatedAt: ZonedDateTime? = null,
) {
    var deleted: Boolean = deleted
        private set                // 외부 직접 변경 금지

    var giteaApiToken: String? = giteaApiToken
        private set

    fun update(giteaApiToken: String? = null) {
        this.giteaApiToken = giteaApiToken
        this.updatedAt = ZonedDateTime.now()
    }

    fun markDeleted() {
        if (deleted) return
        deleted = true
        updatedAt = ZonedDateTime.now()
    }

    companion object { ... }
}
```

// WRONG - constructor에 var로 직접 선언 (외부 변경 가능)
```kotlin
class Member private constructor(
    var deleted: Boolean = false,  // 금지! private set 없이 외부에서 변경 가능
)
```

## 규칙 3: 비즈니스 로직은 도메인 모델 안에

도메인 관련 검증, 계산, 상태 전이 로직은 도메인 모델 내부에 위치한다.

```kotlin
class Alert private constructor(...) {
    init {
        require(price > BigDecimal.ZERO) { "가격은 0보다 커야 합니다" }
    }
    companion object {
        fun create(name: String, price: BigDecimal): Alert =
            Alert(name = name, price = price)  // init 블록에서 검증
    }
}
```

## 규칙 4: Spring/JPA 어노테이션 금지

도메인 모델은 순수 Kotlin 클래스여야 한다. `@Entity`, `@Component` 등 금지.

---

## 규칙 5: 도메인 예외

도메인 레이어의 예외는 **순수 도메인 예외**여야 한다. HTTP 상태·resultCode·messageCode를 알면 안 된다.

### 베이스 클래스

`domain/common/exception/DomainException.kt` — Spring/HTTP/JPA 의존 0의 순수 `abstract class : RuntimeException`.
도메인 예외는 반드시 이 클래스를 상속한다. (unigate 에는 아직 없다. 첫 도메인 예외를 만들 때 함께 만든다.)

```kotlin
// domain/{도메인}/exception/XxxDomainException.kt
sealed class DashboardDomainException(
    message: String,
    cause: Throwable? = null,
) : DomainException(message, cause) {
    class NotFound(message: String) : DashboardDomainException(message)
    class InvalidLayout(message: String) : DashboardDomainException(message)
}
```

- `sealed class`로 서브타입을 한 파일에 봉인. 의미 구분은 sealed 서브타입으로 표현.
- 생성자 시그니처 `(message: String, cause: Throwable? = null)` 통일 — throw 호출부 안정.
- 외부 의존 0: `HttpStatusCode`·`ResponseTypeCodeInterface`·`BaseRuntimeException` 상속·의존 모두 금지(ArchUnit으로 빌드 단계 강제).

### API 계약 번역은 도메인 밖에서

도메인 예외 → API 응답(resultCode·messageCode) 번역은 **도메인 레이어 밖**에서 수행한다.

- `application`: 모듈의 `application/{도메인}/.../exception/enums/XxxExceptionCodeKind`(`ResponseTypeCodeInterface` 구현) 레지스트리에 도메인 코드(resultCode 3200대 등)를 추가하고, `application/{도메인}/.../exception/contract/XxxDomainErrorContract.kt`에 `fun XxxDomainException.toErrorCode(): XxxExceptionCodeKind` (sealed exhaustive) 를 둔다.
- `adapter/common/restIn/advice/<Server>DomainExceptionHandler`: 서버별 단일 `@RestControllerAdvice`에서 번역·응답 조립. 도메인 예외 패밀리별 `@ExceptionHandler` 메서드를 이 클래스에 추가한다 (→ rest-controller skill 참조)

---

## Value Object 규칙

### 도메인 개념은 Value Object로 래핑

원시 타입(Long, String) 대신 도메인 의미를 가진 VO를 사용한다. VO는 **단일 값 VO**와 **복합 값 VO** 두 가지 형태를 가질 수 있다.

### 단일 값 VO — `@JvmInline value class`

필드가 하나뿐인 VO는 반드시 `@JvmInline value class`를 사용한다. (data class 금지)

```kotlin
// domain/alert/vo/AlertId.kt
@JvmInline
value class AlertId(val value: Long)

// domain/alert/vo/StockCode.kt
@JvmInline
value class StockCode(val value: String) {
    init {
        require(value.matches(Regex("^[0-9]{6}$"))) {
            "종목코드는 6자리 숫자여야 합니다"
        }
    }
}
```

### 복합 값 VO — `data class`

여러 필드로 구성된 값 객체는 `data class`로 정의한다. `@JvmInline`은 단일 필드만 허용하므로 복합 VO에는 사용할 수 없다. 필요 시 `init` 블록으로 불변식을 검증하고, `companion object`에 기본값 팩토리(`defaultXxx()`) 또는 외부 표현 변환 팩토리(`fromXxx()`)를 둘 수 있다.

```kotlin
// domain/common/vo/ComputeResources.kt
data class ComputeResources(
    val cpuRequest: Long,
    val cpuLimit: Long,
    val memoryRequest: Long,
    val memoryLimit: Long,
) {
    init {
        require(cpuRequest >= 0) { "cpuRequest must be non-negative" }
        require(cpuLimit >= cpuRequest) { "cpuLimit must be >= cpuRequest" }
    }

    companion object {
        fun defaultResources(): ComputeResources =
            ComputeResources(0, 0, 0, 0)
    }
}

// domain/gitea/vo/UnitAccessLevels.kt — 외부 시스템 표현과의 변환 팩토리를 갖는 복합 VO
data class UnitAccessLevels(
    val code: UnitAccessLevel = UnitAccessLevel.NONE,
    val issues: UnitAccessLevel = UnitAccessLevel.NONE,
    // ...
) {
    fun toUnitsMap(): Map<String, String> = /* ... */

    companion object {
        fun fromGitea(unitsMap: Map<String, String>?, units: List<String>?): UnitAccessLevels = /* ... */
    }
}
```

### VO 규칙 요약

- **위치**: `domain/{도메인}/vo/` 패키지 (단일·복합 모두 동일)
- **단일 값 VO**: 반드시 `@JvmInline value class` 사용 (data class 금지)
- **복합 값 VO**: `data class` 사용. `@JvmInline`은 단일 필드만 지원하므로 복합에는 쓸 수 없다.
- **식별자가 없고 값 자체로 동등성이 판단되는 경우** VO로 분류한다. 식별자(id)가 있고 라이프사이클이 있는 것은 VO가 아니라 **도메인 모델**이다.
- **검증**: 단일·복합 모두 생성 시점에 `init` 블록으로 유효성 보장
- **팩토리**: 복합 VO는 필요 시 `companion object`에 기본값(`defaultXxx()`)이나 외부 표현 변환(`fromXxx()`) 팩토리 제공

## 체크리스트
- [ ] private constructor를 사용하는가? (도메인 모델)
- [ ] create()와 restore() factory method가 있는가? (도메인 모델)
- [ ] val을 우선 사용하는가?
- [ ] 비즈니스 검증 로직이 도메인 모델 안에 있는가?
- [ ] Spring/JPA 어노테이션이 없는가?
- [ ] ID 타입이 @JvmInline value class VO로 래핑되어 있는가?
- [ ] 단일 값 VO는 `@JvmInline value class`를, 복합 값 VO는 `data class`를 사용했는가?
- [ ] VO가 domain/{도메인}/vo/ 패키지에 있는가?
- [ ] 도메인 예외가 `DomainException` 상속·순수(HTTP 계약 미의존)인가?
