---
name: kotlin-style
description: 프로젝트 고유 Kotlin 스타일 규칙(Boolean 네이밍 is 접두사 금지, ktlint 포맷팅). Kotlin 코드 작성 시 참조한다.
---

# Kotlin 스타일 규칙

## Boolean 네이밍
Boolean 프로퍼티에 `is` 접두사를 붙이지 않는다.

// WRONG
```kotlin
data class ProjectResponse(
    val isActive: Boolean,
    val isLocked: Boolean,
)
```

// CORRECT
```kotlin
data class ProjectResponse(
    val active: Boolean,
    val locked: Boolean,
)
```

## 패키지 네이밍 (소문자 표준)
- 포트 패키지: `port/inbound`, `port/outbound` — 대문자 `inBound`/`outBound` 금지
- driving adapter: `restIn` — 소문자 `restin` 금지
- 대소문자만 바뀌는 패키지 rename 시 macOS(대소문자 비구분 FS)에서 빌드 캐시가 옛 경로로 잔존할 수 있으므로 **`clean` 재빌드로 검증**

## HTTP 상태 코드
- **adapter 계층에서는 Spring `org.springframework.http.HttpStatus` 를 쓴다.** 예: `HttpStatus.BAD_GATEWAY`
  - unigate 에는 별도 상태코드 상수 모듈이 없다. 표준 타입을 그대로 쓰는 편이 낫다.
- **domain 계층에는 HTTP 상태값을 두지 않는다.** `HttpStatus` import 자체가 레이어 위반이다.
  도메인은 "무슨 일이 일어났는가"만 표현하고, 그것을 몇 번 코드로 응답할지는 adapter 가 정한다.
  - 이 경계가 무너지면 도메인이 HTTP 프로토콜에 묶여 다른 진입점(배치·메시지 소비)에서 재사용할 수 없게 된다.

## ktlint
- `./gradlew ktlintFormat`으로 자동 포맷팅. **pre-commit**(format)·**pre-push**(check) git hook이 gradle 빌드 시 자동 설치된다.
- push 차단 시 `./gradlew ktlintFormat` 후 재시도(우회 `--no-verify`, 단 CI가 최종 게이트).
