---
name: architecture
description: 헥사고날 아키텍처(Ports & Adapters) 레이어 구조와 의존성 방향 규칙. 새 기능 추가, 리팩토링, 아키텍처 관련 작업 시 참조한다.
---

# 헥사고날 아키텍처 & 의존성 방향 규칙

## 레이어 구조

```
src/main/kotlin/{basePackagePath}/
├── config/
├── adapter/
│   └── {도메인}/
│       ├── restIn/          # Driving Adapter (REST Controller)
│       │   ├── controller/
│       │   ├── dto/
│       │   └── mapper/
│       └── jpaOut/          # Driven Adapter (JPA)
│           ├── entity/
│           ├── repository/
│           ├── service/     # OutPort 구현체
│           └── mapper/
├── application/
│   └── {도메인}/
│       ├── service/         # UseCase 구현
│       ├── port/
│       │   ├── inbound/    # InPort (UseCase 인터페이스)
│       │   └── outbound/   # OutPort (영속성 인터페이스)
│       ├── dto/
│       ├── mapper/
│       └── exception/
└── domain/
    └── {도메인}/
        ├── CLAUDE.md        # 도메인 상세 명세
        ├── model/
        ├── vo/
        ├── enums/
        └── event/
```

## 4가지 포트 역할

| 포트 | 위치 | 역할 | 예시 |
|------|------|------|------|
| InPort | application/{도메인}/port/inbound/ | UseCase 인터페이스 | `CreateAlertInPort` |
| OutPort | application/{도메인}/port/outbound/ | 영속성 인터페이스 | `SaveAlertOutPort` |
| Driving Adapter | adapter/{도메인}/restIn/ | InPort 호출 | REST Controller |
| Driven Adapter | adapter/{도메인}/jpaOut/ | OutPort 구현 | JPA Repository |

## 새 기능 추가 시 생성 순서

1. Domain Model (`domain/{도메인}/model/`)
2. InPort 인터페이스 (`application/{도메인}/port/inbound/`)
3. OutPort 인터페이스 (`application/{도메인}/port/outbound/`)
4. UseCase 구현 (`application/{도메인}/service/`)
5. JPA Entity + Driven Adapter (`adapter/{도메인}/jpaOut/`)
6. REST Controller + Driving Adapter (`adapter/{도메인}/restIn/`)

도메인 예외가 있는 경우 추가 순서:

7. 도메인 예외 (`domain/{도메인}/exception/XxxDomainException.kt`, `sealed : DomainException`)
8. application 계약 — 모듈의 `application/{도메인}/.../exception/enums/XxxExceptionCodeKind`(`ResponseTypeCodeInterface` 구현) 레지스트리에 도메인 코드(resultCode)를 추가하고, `application/{도메인}/.../exception/contract/XxxDomainErrorContract.kt`에 `fun XxxDomainException.toErrorCode(): XxxExceptionCodeKind` (sealed exhaustive) 를 둔다
9. 경계 advice (서버별 `adapter/common/restIn/advice/<Server>DomainExceptionHandler`에 `@ExceptionHandler` 메서드 추가)

## 의존성 방향

```
adapter → application → domain (단방향만 허용)
```

- **domain**: 외부 의존성 없음. 순수 비즈니스 로직만 포함. Spring 어노테이션 금지.
- **application**: domain에만 의존. 포트 인터페이스를 통해 외부와 소통. adapter 패키지 import 금지.
- **adapter**: application의 포트를 구현하거나 호출.

### 역방향 의존성 절대 금지

// WRONG - domain이 application을 의존
```kotlin
// domain/alert/model/Alert.kt
import {basePackage}.application.alert.dto.CreateAlertCommand  // 금지!
```

// WRONG - application이 adapter를 의존
```kotlin
// application/alert/service/CreateAlertUseCase.kt
import {basePackage}.adapter.alert.jpaOut.repository.AlertJpaRepository  // 금지!
```

// CORRECT - application은 OutPort 인터페이스만 의존
```kotlin
// application/alert/service/CreateAlertUseCase.kt
import {basePackage}.application.alert.port.outbound.SaveAlertOutPort  // OK
```

// WRONG - OutPort가 InPort를 의존 (포트 간 역방향, 어댑터가 UseCase를 역호출하는 안티패턴)
```kotlin
// application/alert/port/outbound/SaveAlertOutPort.kt
import {basePackage}.application.alert.port.inbound.CreateAlertInPort  // 금지!
```

### domain에 금지되는 것들
- Spring 어노테이션 (`@Component`, `@Service`, `@Entity` 등)
- JPA 어노테이션 (`@Id`, `@Column`, `@Table` 등)
- 외부 라이브러리 import (Jackson, etc.)
- application/adapter 패키지 import
- Spring `org.springframework.http.HttpStatus` 등 **HTTP 프로토콜 타입** — 상태코드는 adapter 가 정한다
- HTTP/응답 계약 타입 상속·의존 — 도메인 예외는 순수 `RuntimeException` 파생으로 두고,
  그것을 어떤 상태코드로 응답할지는 adapter 경계에서 매핑한다

> `HttpStatus` 는 adapter 계층에서는 **권장 타입**이다 (예: `DownstreamErrorMappingFilter`).
> 금지 대상은 "HttpStatus 사용" 자체가 아니라 **domain 이 HTTP 를 아는 것**이다.

## ArchUnit으로 자동 강제되는 규칙 (BC 모듈)

아래 규칙은 `*-build-test` CI(`:module:build`)와 로컬에서 ArchUnit으로 검증되어 **위반 시 빌드 실패**한다. 상세: `docs/testing/archunit-architecture-guide.md`.

- 레이어 방향: `adapter → application → domain` (역방향 deny)
- `application → adapter` import 금지 / `domain → application·adapter·Spring·JPA` 금지
- 포트 네이밍: `port/inbound` 인터페이스 `*InPort`, `port/outbound` 인터페이스 `*OutPort`
- 포트 의존 방향: `port/outbound`(OutPort)는 `port/inbound`(InPort)를 **의존 금지**. `InPort → OutPort`는 정상이나 그 역방향(OutPort가 UseCase를 역호출)은 책임 분리를 깨는 안티패턴
- service 배치: `service` 직하 public 클래스는 `*UseCase`. **UseCase에서 분리한 협력 컴포넌트(주입 대상)는 `service/components`에 둔다** (파일 전용 private helper는 예외)
- 패키지 순환 의존 금지
- `domain` 은 HTTP/응답 계약 타입(`BaseRuntimeException`/`ResponseTypeCodeInterface`/`HttpStatusCode`)을 의존하지 않는다
- `domain` 의 예외(Throwable) 클래스는 `DomainException`(system-core)을 상속한다 (도메인 예외 없는 모듈은 통과)
- 모듈 내 도메인 예외(`DomainException` 서브타입)는 모두 그 모듈 adapter 의 `@ExceptionHandler`로 처리돼야 한다 (누락 시 빌드 실패)

### 패키지 네이밍 (소문자 표준)
- `port/inbound`, `port/outbound` (대문자 `inBound`/`outBound` 금지)
- driving adapter는 `restIn` (소문자 `restin` 금지)
- 대소문자만 바뀌는 rename은 macOS에서 `clean` 재빌드로 검증

## 체크리스트
- [ ] 새 파일이 올바른 레이어(adapter/application/domain)에 위치하는가?
- [ ] 도메인별 패키지 안에 있는가?
- [ ] InPort/OutPort 인터페이스가 application 레이어에 있는가?
- [ ] Adapter가 adapter 레이어에 있는가?
- [ ] domain 패키지에 Spring/JPA 어노테이션이 없는가? (HTTP 상태는 `HttpStatusCode` 상수)
- [ ] application에서 adapter 패키지를 import하지 않는가?
- [ ] 의존성 방향이 adapter → application → domain 인가?
- [ ] 포트/패키지 네이밍이 소문자(`inbound`/`outbound`/`restIn`)인가?
- [ ] OutPort가 InPort를 import하지 않는가? (포트 의존 방향: InPort→OutPort만 허용)
- [ ] service 직하는 UseCase뿐이고, 협력 컴포넌트는 `service/components`에 있는가?
- [ ] 도메인 예외가 DomainException 상속·HTTP 계약 미의존인가?
- [ ] 도메인 예외에 대응하는 모듈 로컬 advice(`adapter/.../restIn/advice/`)가 있는가?
