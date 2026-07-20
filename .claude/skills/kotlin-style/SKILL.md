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

## HTTP 상태 코드 상수
- HTTP 상태값은 Spring `org.springframework.http.HttpStatus` 대신 **`com.nhn.inje.ccp.constant.HttpStatusCode`** 상수를 사용한다(domain 순수성, ArchUnit 강제). 예: `HttpStatusCode.BAD_REQUEST`

## ktlint
- `./gradlew ktlintFormat`으로 자동 포맷팅. **pre-commit**(format)·**pre-push**(check) git hook이 gradle 빌드 시 자동 설치된다.
- push 차단 시 `./gradlew ktlintFormat` 후 재시도(우회 `--no-verify`, 단 CI가 최종 게이트).
