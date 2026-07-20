---
name: convention-check
description: 프로젝트 아키텍처·컨벤션 스킬들을 기준으로 코드를 검증하고 위반 사항을 리포트한다
user_invocable: true
---

# 컨벤션 검증

프로젝트에 정의된 아키텍처·컨벤션 스킬들을 **직접 읽어서** 대상 코드를 검증하고, 위반 사항을 리포트한다.

## 적용 시점

- 새 기능 구현 완료 후 셀프 리뷰
- PR 생성 전 컨벤션 점검
- "컨벤션 체크해줘", "검증해줘", "리뷰해줘" 요청 시
- 리팩토링 후 규칙 준수 확인

---

## 검증 절차

### 1단계: 대상 파일 수집
- 인자가 없으면 `git diff --name-only`로 변경된 파일 목록을 수집한다
- 인자로 파일/디렉토리 경로가 주어지면 해당 범위만 검증한다
- `.kt` 소스 파일만 대상으로 한다 (설정 파일, SKILL.md 등 제외)

### 2단계: 카테고리 분류 및 스킬 파일 로드
파일 경로 패턴으로 해당 검증 카테고리를 결정한다. 하나의 파일이 여러 카테고리에 해당할 수 있다.

| 파일 경로 패턴 | 참조할 스킬 파일 |
|----------------|-----------------|
| `domain/**/model/*.kt`, `domain/**/vo/*.kt` | `.claude/skills/domain-model/SKILL.md` |
| `adapter/**/jpaOut/**/*.kt` | `.claude/skills/jpa-entity/SKILL.md` |
| `adapter/**/restIn/controller/*.kt`, `adapter/**/restIn/dto/*.kt` | `.claude/skills/rest-controller/SKILL.md` |
| `adapter/**/restIn/mapper/*.kt`, `adapter/**/jpaOut/mapper/*.kt`, `application/**/mapper/*.kt` | `.claude/skills/mapper/SKILL.md` |
| `application/**/service/*.kt`, `application/**/port/**/*.kt`, `application/**/dto/*.kt` | `.claude/skills/usecase/SKILL.md` |
| `**/*UseCase*.kt`, `**/*Adapter*.kt`, `**/*Controller*.kt` | `.claude/skills/db-query-review/SKILL.md` |
| `**/*.kt` | `.claude/skills/kotlin-style/SKILL.md` |
| `**/*.kt` | `.claude/skills/architecture/SKILL.md` (의존성 방향 검사) |
| `build-logic/**`, `*.gradle.kts` | `.claude/skills/setup-gradle/SKILL.md` |
| `**/*Test*.kt`, `**/*Spec*.kt` | `.claude/skills/testing/SKILL.md` |
| `domain/**/exception/*.kt`, `**/*DomainException*.kt` (도메인 레이어 예외) | `.claude/skills/domain-model/SKILL.md` + `.claude/skills/architecture/SKILL.md` |
| `**/exception/*.kt`(BaseRuntimeException 계열), `**/*ExceptionUtils*.kt`, `**/BaseRuntimeException.kt`, `**/PropagatedDetailMessage*` 관련 파일 | `.claude/skills/detail-message-propagation/SKILL.md` |

**중요: 매칭된 스킬 파일을 Read 도구로 직접 읽어서 해당 규칙을 파악한 후 검증한다.**
규칙을 이 파일에 복사하지 않는다. 항상 원본 스킬 파일을 Single Source of Truth로 사용한다.

### 3단계: 카테고리별 검증 실행
로드한 스킬 파일의 규칙을 기준으로 대상 코드를 검사한다.

검증 시 아래 심각도 기준을 적용한다:

| 심각도 | 기준 | 예시 |
|--------|------|------|
| **CRITICAL** | 아키텍처 위반 (의존성 방향, 레이어 경계) | domain이 adapter를 import, UseCase가 Repository 직접 의존 |
| **HIGH** | 스킬에 명시된 컨벤션 위반 | 도메인 생성자 노출, Entity comment 누락, DTO 파일 분리 미준수 |
| **MEDIUM** | 개선 권고 (동작에는 문제없음) | Controller에 OutPort 과다 의존, 캐시 미적용 |

### 4단계: 리포트 출력

---

## 리포트 포맷

검증 결과는 아래 형식으로 출력한다.

```markdown
## 컨벤션 검증 결과

검증 파일: N개 | 위반: X개 | 경고: Y개 | 통과: Z개

### CRITICAL (즉시 수정 필요)

| 파일 | 참조 스킬 | 위반 내용 |
|------|-----------|-----------|
| `domain/user/model/User.kt:3` | architecture | `@Entity` 어노테이션 사용 — 의존성 방향 위반 |

### HIGH (수정 권고)

| 파일 | 참조 스킬 | 위반 내용 |
|------|-----------|-----------|
| `application/user/dto/UserCommand.kt` | usecase | Command가 `Command.kt` 외 파일에 정의됨 |

### MEDIUM (개선 제안)

| 파일 | 참조 스킬 | 내용 |
|------|-----------|------|
| `adapter/user/restIn/controller/UserController.kt` | rest-controller | OutPort 4개 직접 의존 |

### 통과 항목
- kotlin-style: 위반 없음
- testing: 위반 없음
```

---

## 자동 수정

리포트 출력 후, CRITICAL과 HIGH 위반에 대해 자동 수정을 제안한다.
사용자가 동의하면 수정을 진행하고, 수정 후 재검증한다.
