---
name: usecase
description: UseCase 패턴(OutPort만 의존, Result DTO 반환, 오케스트레이션)과 Application DTO(Command/Spec/Result/Query) 파일 분리 규칙. UseCase, Port, DTO 작성 시 참조한다.
---

# UseCase 패턴 & DTO 규칙

## UseCase 핵심 규칙

### 규칙 1: UseCase는 OutPort만 의존

UseCase(Application Layer)는 OutPort 인터페이스에만 의존한다. Repository나 Adapter 구현체를 직접 의존하지 않는다.

// WRONG - Repository 직접 의존
```kotlin
@Service
class CreateAlertUseCase(
    private val alertRepository: AlertJpaRepository,  // 금지!
) : CreateAlertInPort { ... }
```

// CORRECT - OutPort 인터페이스만 의존, Result DTO 반환
```kotlin
@Service
class CreateAlertUseCase(
    private val saveAlertOutPort: SaveAlertOutPort,
) : CreateAlertInPort {
    override fun execute(command: CreateAlertCommand): AlertResult {
        val alert = Alert.create(
            stockCode = command.stockCode,
            targetPrice = command.targetPrice,
        )
        return saveAlertOutPort.save(alert).toResult()
    }
}
```

### 규칙 2: InPort 반환 타입은 Result DTO

- **InPort 반환**: `XxxResult`, `Page<XxxResult>`, `List<XxxResult>` (도메인 모델 반환 금지)
- **OutPort 반환**: 기본적으로 **도메인 모델** 반환. 도메인 모델로 표현 불가 시 `Query` DTO 사용.
- **변환 위치**: UseCase의 `return` 시점에서 `.toResult()` 호출
- **void 연산**: 삭제 등 반환값 없는 경우 `Unit` 사용

### 규칙 3: 도메인 모델이 없는 경우 (간소화 패턴)

DB 저장이 필요 없고, 복잡한 비즈니스 로직이 없는 경우(예: K8S 리소스 단순 생성/조회) Domain 레이어 없이 UseCase에서 직접 Command → OutPort 호출이 가능하다.

```kotlin
@Service
class CreateNamespaceUseCase(
    private val createK8sResourceOutPort: CreateK8sResourceOutPort,
) : CreateNamespaceInPort {
    override fun execute(command: CreateNamespaceCommand): CreateNamespaceResult {
        // Domain 모델 없이 직접 리소스 구성
        val namespace = NamespaceBuilder()
            .withNewMetadata()
                .withName(command.name)
                .addToLabels("managed-by", "unigate")
            .endMetadata()
            .build()

        val created = createK8sResourceOutPort.execute(namespace, k8sUser) as Namespace
        return CreateNamespaceResult(name = created.metadata.name)
    }
}
```

**이 패턴을 사용하는 조건** (모두 충족해야 함):
- DB 저장이 필요 없음
- RBAC/권한 설정이 필요 없음
- 복잡한 비즈니스 로직이 없음
- 단순 외부 리소스 생성/조회만 하는 경우

### 규칙 4: UseCase에 비즈니스 로직 넣지 않기

도메인 모델이 있는 경우, 비즈니스 로직(검증, 계산, 상태 전이)은 Domain Model에 위치. UseCase는 오케스트레이션만 담당.

// CORRECT - Domain Model에 비즈니스 로직, UseCase는 오케스트레이션
```kotlin
@Service
class TriggerAlertUseCase(
    private val findAlertOutPort: FindAlertOutPort,
    private val saveAlertOutPort: SaveAlertOutPort,
) : TriggerAlertInPort {
    override fun execute(alertId: AlertId) {
        val alert = findAlertOutPort.findById(alertId)
        val triggered = alert.trigger()  // 도메인 모델 내부에서 검증+상태변경
        saveAlertOutPort.save(triggered)
    }
}
```

### 규칙 5: 네이밍

- 위치: `application/{도메인}/service/`
- 네이밍: `{동사}{도메인}UseCase` (예: `CreateAlertUseCase`, `GetAlertUseCase`)
- InPort 구현: 반드시 해당 InPort 인터페이스를 implements

#### 동사 선택 기준

기본 동사 5개를 우선 사용한다: `Get`, `Create`, `Delete`, `Update`, `List`

단, 기본 동사로는 의미가 부정확할 때 더 적절한 동사를 자유롭게 선택할 수 있다.
예: `Register`, `Sync`, `Assign`, `Trigger`, `Initialize` 등 — 행위의 의미를 가장 잘 드러내는 동사를 쓴다.

**금지 사항**: 같은 의미의 동사를 혼용하지 않는다. 예를 들어 삭제 의미로 `Delete`와 `Remove`를 섞어 쓰지 않는다.

### 규칙 6: Transactional Outbox — 이벤트 발행은 UseCase 내부에서

비즈니스 write와 Outbox 이벤트 write는 **반드시 같은 트랜잭션**에서 원자적으로 커밋되어야 한다. 이 규칙을 지키는 가장 단순한 방법은 **UseCase가 `@Transactional` 범위 안에서 `PublishXxxOutPort`를 직접 호출**하는 것이다.

#### 왜 UseCase에서 publish를 호출하는가

- Transactional Outbox 패턴은 "비즈니스 커밋 ↔ 이벤트 INSERT"가 단일 원자 단위일 때만 성립한다.
- Controller에서 "UseCase 호출 → publish 호출"로 쪼개면 **두 TX로 분리**되어 Outbox 원자성이 깨진다. DB 커밋 후 publish 실패 시 Consumer가 영영 깨어나지 않고, 외부 시스템 drift가 고착된다.
- UseCase는 `PublishXxxOutPort` **인터페이스**에만 의존하므로 "outbox/valkey/in-memory" 같은 구현 선택은 Adapter에 숨어 있다. 레이어 경계는 보존된다.

```kotlin
// CORRECT — Transactional Outbox 패턴
@Service
@Transactional
class CreateProjectUseCase(
  private val createProjectOutPort: CreateProjectOutPort,
  private val operationTrackingOutPort: OperationTrackingOutPort,
  private val publishSagaEventOutPort: PublishSagaEventOutPort,
) : CreateProjectInPort {
  override fun execute(command: CreateProjectCommand): CreateProjectResult {
    val projectId = createProjectOutPort.execute(project)             // 같은 TX
    val operationId = operationTrackingOutPort.start(...)             // 관계 기록
    publishSagaEventOutPort.publish(ProjectCreateStepEvent(...))      // 같은 TX
    return CreateProjectResult(projectId, operationId)
  }
}
```

```kotlin
// WRONG — Controller에서 publish 호출 (TX 분리)
class ProjectV1Controller(
  private val createProjectInPort: CreateProjectInPort,
  private val publishSagaEventOutPort: PublishSagaEventOutPort,  // 금지!
) {
  @PostMapping
  fun createProject(...): ResponseEntity<...> {
    val projectId = createProjectInPort.execute(command)  // TX 1: 커밋
    publishSagaEventOutPort.publish(event)                 // TX 2: 실패 가능 → 원자성 깨짐
    ...
  }
}
```

#### 레이어 경계에 대한 오해

"UseCase는 이벤트 인프라를 몰라야 한다"는 원칙은 **구현 디테일 은닉**을 의미하지 `OutPort 호출 금지`가 아니다.

- UseCase → `PublishXxxOutPort` (인터페이스) ✅ 헥사고날 규칙 준수
- UseCase → `OutboxEventRepository` (구현체) ❌ 레이어 위배

즉, `CreateProjectOutPort.execute(project)`가 JPA 구현인지 UseCase가 모르는 것과 **동일한 추상화 수준**으로 `PublishXxxOutPort`도 취급한다.

#### Controller가 해야 할 일

- Request → Command 매핑 (Mapper 경유)
- InPort 호출 1회
- Result → Response 매핑 (Mapper 경유)
- 202 Accepted 등 HTTP 상태 결정

**Controller는 `Publish*OutPort`를 주입받지 않는다.** 이벤트 발행은 비즈니스 작업의 일부이며, 그 경계는 UseCase가 가진다.

#### 체크리스트 — Transactional Outbox가 필요한 경우

아래 중 하나라도 해당하면 **UseCase 내부 publish + @Transactional** 필수.

- [ ] DB write와 함께 이벤트 발행이 필요한 작업
- [ ] Operation Tracking(202 Accepted + 비동기 폴링) 개시
- [ ] Reconciler가 drift를 감지해 복구해야 하는 비동기 작업
- [ ] 외부 시스템(Keycloak/Gitea/ArgoCD 등)과 일관성이 필요한 작업

#### 예외: 이벤트 발행이 불필요한 경우

순수 DB 단일 작업(예: soft-delete, 멤버 역할 삭제)은 Saga/Outbox가 불필요하다. `@Transactional` 단일 TX만 두고 publish는 호출하지 않는다. Controller도 `Publish*OutPort` 주입이 없다.

### 규칙 7: Port 인터페이스 KDoc

InPort, OutPort 인터페이스에는 반드시 KDoc을 작성한다. 인터페이스 레벨과 메서드 레벨 모두 작성한다.

```kotlin
/**
 * 알림을 생성하는 InPort.
 *
 * 알림 정보를 받아 저장하고 결과를 반환한다.
 */
interface CreateAlertInPort {
  /**
   * 알림을 생성한다.
   *
   * @param command 알림 생성에 필요한 정보
   * @return 생성된 알림 정보
   */
  fun execute(command: CreateAlertCommand): AlertResult
}
```

---

## Application DTO 규칙

### DTO 분류

| 유형 | 용도 | 네이밍 | 예시 |
|------|------|--------|------|
| Command | 쓰기 요청 (생성/수정/삭제) | `{동사}{도메인}Command` | `CreateAlertCommand` |
| Spec | OutPort에 전달하는 검색/필터 조건 | `{도메인}SearchSpec` | `AlertSearchSpec` |
| Result | InPort에서 반환하는 응답 | `{도메인}Result` | `AlertResult` |
| Query | OutPort에서 도메인 모델로 표현 불가한 반환값 | 필요 시 정의 | 집계, 조인 결과 |

### DTO 파일 분리 규칙

역할별 파일에 관련 클래스를 모아서 작성한다. 클래스마다 개별 파일을 만들지 않는다.

```
application/{도메인}/dto/
├── Command.kt      # 모든 Command data class
├── Spec.kt         # 모든 검색 조건 data class (있는 경우만)
├── Result.kt       # 모든 Result data class (있는 경우만)
└── Query.kt        # OutPort 반환용 (도메인 모델 불가 시, 있는 경우만)
```

- 해당 역할의 DTO가 없으면 파일을 만들지 않는다
- 모든 DTO는 `data class`로 정의

### Adapter DTO와 Application DTO 분리

| 레이어 | DTO 유형 | 위치 |
|--------|---------|------|
| Adapter (REST) | Request / Response | `adapter/{도메인}/restIn/dto/` |
| Application | Command / Query / Result | `application/{도메인}/dto/` |

## 체크리스트
- [ ] UseCase가 OutPort 인터페이스만 의존하는가?
- [ ] InPort 반환 타입이 Result DTO인가?
- [ ] UseCase는 오케스트레이션만 담당하는가?
- [ ] DTO가 data class인가?
- [ ] 역할별 파일(Command.kt, Result.kt 등)에 모아서 작성했는가?
- [ ] Adapter DTO와 Application DTO가 분리되어 있는가?
- [ ] Port 인터페이스(InPort, OutPort)에 KDoc이 작성되었는가?
- [ ] 새 API 엔드포인트에 대한 .http 테스트 파일이 추가되었는가?
