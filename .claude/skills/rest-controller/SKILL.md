---
name: rest-controller
description: REST 컨트롤러 규칙(InPort 호출, Request/Response DTO 분리, 매퍼 패턴). REST API 컨트롤러 작성/수정 시 참조한다.
---

# REST 컨트롤러 규칙

## 규칙 1: 위치
REST 컨트롤러는 `adapter/{도메인}/restIn/controller/` 패키지에 위치한다.

## 규칙 2: InPort를 통해 UseCase 호출
Controller는 InPort 인터페이스 타입을 주입받아 사용한다.

// WRONG - UseCase 구현체 직접 의존
```kotlin
@RestController
class AlertController(
    private val createAlertUseCase: CreateAlertUseCase,  // 구현체 타입
)
```

// CORRECT - InPort 인터페이스 의존
```kotlin
@RestController
class AlertController(
    private val createAlertInPort: CreateAlertInPort,  // 인터페이스 타입
)
```

## 규칙 3: Request/Response DTO 분리
Controller의 Request/Response DTO는 `adapter/{도메인}/restIn/dto/` 패키지에 위치.
Application 레이어의 Command/Result와는 별도로 정의하고 매퍼로 변환.

// WRONG - Application DTO를 Controller에서 직접 사용
```kotlin
@PostMapping("/alerts")
fun createAlert(@RequestBody command: CreateAlertCommand): CreateAlertResult {
    return createAlertInPort.execute(command)
}
```

// CORRECT - Adapter DTO로 변환
```kotlin
@PostMapping("/alerts")
fun createAlert(@RequestBody request: CreateAlertRequest): CreateAlertResponse {
    val command = request.toCommand()
    val result = createAlertInPort.execute(command)
    return CreateAlertResponse.from(result)
}
```

## 규칙 4: Mapper — MapStruct 기본, 확장함수 허용
REST 매퍼 파일(`adapter/{도메인}/restIn/mapper/`)에서는 변환 유형에 따라 패턴을 선택한다.

- **Request → Command**: MapStruct 인터페이스만 사용
- **Result → Response (단순 1:1 매핑)**: MapStruct 인터페이스 사용
- **Result → Response (필드 조합/가공 필요)**: 확장함수 사용

```kotlin
// MapStruct — 단순 1:1 매핑
@Mapper(componentModel = "spring")
interface AlertRestMapper {
    fun toCommand(request: CreateAlertRequest): CreateAlertCommand
    fun toResponse(result: AlertResult): AlertResponse
}

// 확장함수 — 필드 조합/가공이 필요한 경우
fun CreateTektonResourceResult.toCreateResponse(): CreateTektonResourceResponse =
    CreateTektonResourceResponse(
        kind = kind,
        success = success,
        gitOpsDetail = syncSuccess?.let { GitOpsDetail(syncSuccess = it, path = path) },
    )
```

다중 파라미터 매핑 시 `@Mapping`으로 소스를 명시:

```kotlin
@Mapper(componentModel = "spring")
interface DepartmentRestMapper {
    @Mapping(source = "id", target = "id")
    fun toUpdateCommand(id: Long, request: UpdateDepartmentRequest): UpdateDepartmentCommand
}
```

## 규칙 5: 도메인 예외 경계 번역 — 서버별 단일 모듈 로컬 advice

도메인 레이어에서 `DomainException`(system-core)을 상속한 sealed 예외가 발생하면, **서버(모듈)별 단일 `@RestControllerAdvice`** 에서 API 응답으로 번역한다. 글로벌 핸들러(`BaseRuntimeException` 처리)와 타입이 분리된다.

### 위치

`adapter/common/restIn/advice/<Server>DomainExceptionHandler.kt`

- 서버 단위로 단일 핸들러를 둔다. 도메인 예외 패밀리별 `@ExceptionHandler` 메서드를 이 클래스 안에 추가한다.
- 예시: `portalManagement` 서버 → `PortalManagementDomainExceptionHandler`

### 패턴

```kotlin
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class PortalManagementDomainExceptionHandler(
    private val messageSource: MessageSource,
) {
    @ExceptionHandler(DashboardDomainException::class)
    fun handle(ex: DashboardDomainException): ResponseEntity<CommonResponse<Map<String, Any>?>> {
        val code = ex.toErrorCode()                                  // application 계약(sealed exhaustive)
        return DomainErrorResponseBuilder.build(code, messageSource) // core/common-web 공용 빌더
    }
    // 새 도메인 예외 추가 시 @ExceptionHandler 메서드를 이 클래스에 추가한다.
}
```

### 규칙

- 핸들러 위치는 `adapter/common/restIn/advice/` (소문자 패키지).
- 서버 단위로 단일 핸들러(`<Server>DomainExceptionHandler`)를 유지한다. 도메인 예외 패밀리별로 파일을 분리하지 않는다.
- `toErrorCode()`로 모듈 레지스트리(`XxxExceptionCodeKind : ResponseTypeCodeInterface`)를 참조한다. enum 위치는 `application/{도메인}/.../exception/enums/`, 확장함수(`toErrorCode()`) 위치는 `application/{도메인}/.../exception/contract/`. domain 타입을 직접 노출하지 않는다.
- 공용 빌더 `DomainErrorResponseBuilder`(core/common-web)로 응답을 조립한다(포맷 일관성).
- resultCode 는 도메인 ErrorCode 간 전역 유일해야 한다(충돌 금지). messageCode 네이밍은 `error.<도메인>.<상세>` 규칙을 따른다.
- 글로벌 핸들러(`BaseRuntimeException` 경로)와 타입이 분리되어 충돌하지 않는다.
- 도메인 예외의 advice 처리는 ArchUnit 가드로 강제된다(미처리 시 빌드 실패).

## 규칙 6: 비동기 mutation 응답(202 Accepted) — Flat-Minimal 규약

비동기 mutation 엔드포인트(202 Accepted 반환)는 아래 규약을 따른다.

- 루트 Response에 **`operationId: UUID`를 반드시 포함**한다. FE는 이를 Operation Tracking 키로 사용한다.
- `resourceType`, `operationType`, `status`, `statusUrl` 등 추적 메타 필드는 **루트에 노출하지 않는다**.
- 폴링 엔드포인트(`GET /v1/operations/{operationId}`) 같은 운영 정보는 **스웨거 `@Operation` description + `@ApiResponse` JSON example**로만 문서화한다.
- Response 조립은 Controller 내부 수동 생성 대신 **Mapper 한 경로로만** 수행한다(`AcceptedResponse.of(...)`와 같은 중간 DTO 조립 금지).

```kotlin
// Response DTO — 루트에 id(동기 확정 PK) + operationId만
data class CreateProjectResponse(
  @field:Schema(description = "프로젝트 ID", example = "550e8400-e29b-41d4-a716-446655440000")
  val id: UUID,
  @field:Schema(
    description = "Operation Tracking ID (FE 폴링 키 — /v1/operations/{operationId})",
    example = "b79f77a2-3faf-47f7-a407-91b855e6224a",
  )
  val operationId: UUID,
)

// Controller — mapper 한 경로로만
@PostMapping
fun createProject(...): ResponseEntity<CommonResponse<CreateProjectResponse?>> {
  val result = createProjectInPort.execute(command)
  return ResponseEntity
    .status(HttpStatus.ACCEPTED)
    .body(CommonResponse.ok(projectRestMapper.toCreateResponse(result)))
}
```

규약 근거: `docs/plans/external-integration-resilience-followup/dto-contract-review.md` §7 (2026-04-23 확정 — A1-Slim + Flat-Minimal).
재검토 트리거: 공통 필드가 2개 이상으로 늘어나거나(`correlationId`·`traceId` 등) 새 비동기 엔드포인트 추가 시 Interface 강제 방식 재평가.

## 체크리스트
- [ ] Controller가 adapter/{도메인}/restIn/controller/에 있는가?
- [ ] InPort 인터페이스를 통해 호출하는가?
- [ ] Request/Response DTO가 adapter 레이어에 있는가?
- [ ] Request→Command 변환에 MapStruct를 사용하는가?
- [ ] Result→Response 변환이 단순 1:1이면 MapStruct, 필드 조합/가공이면 확장함수를 사용하는가?
- [ ] 비동기 mutation(202) Response의 루트에 `operationId: UUID`만 있고 tracking 메타 필드(`resourceType`·`operationType`·`status`·`statusUrl`)가 빠져 있는가?
- [ ] 비동기 mutation Response 조립을 Controller에서 수동으로 하지 않고 Mapper 한 경로로만 수행하는가?
- [ ] 도메인 예외가 있으면 `adapter/common/restIn/advice/<Server>DomainExceptionHandler`에 `@ExceptionHandler` 메서드가 있는가? (서버별 단일 핸들러)
- [ ] advice가 `DomainErrorResponseBuilder`(core/common-web)로 응답을 조립하는가?
