# unigate Claude Code Skills

헥사고날/Kotlin 규칙 skill 큐레이션. 규칙은 공용이므로 그대로 사용하되,
**WebFlux/Reactive 스택 차이**가 있는 부분은 아래 주의사항을 따른다.

| Skill | 용도 | unigate 적용 주의 |
|-------|------|------------------|
| `kotlin-style` | Kotlin 스타일 규칙 (ktlint) | 그대로 적용 |
| `architecture` | 헥사고날 레이어·의존성 방향 | 그대로 적용 (adapter→application→domain) |
| `domain-model` | 도메인 모델 규칙 | 그대로 적용 |
| `usecase` | UseCase(포트 의존, Result DTO) | UseCase 는 **suspend 함수**로 작성 (coroutine) |
| `testing` | Kotest BehaviorSpec + MockK | 그대로 적용. WebFlux 통합 테스트는 `WebTestClient` 사용 |
| `convention-check` | 컨벤션 검증 | 그대로 적용 |
| `ai-memory-plan` | 복잡 작업 맥락 유지 | 그대로 적용 |
| `rest-controller` | ⚠️ **MVC 기반** | unigate 는 SCG(WebFlux) → `@RestController`/`RouterFunction`·`GlobalFilter` 어댑터로 **개작 필요**. adapter/gatewayIn 작성 시 참고만. |

## unigate 전용 (신규 작성)

| Skill | 용도 | 비고 |
|-------|------|------|
| `git-flow` | 브랜치 → PR → **스쿼시 머지** → main pull → 다음 브랜치 | public 리모트이므로 커밋 전 유출 점검이 필수 단계 |
| `learning-doc` | `docs/learning/` 학습 문서 작성 | **"4. 직접 확인한 것" · "6. 남은 의문"은 사용자가 작성**한다 (AI 대필 금지) |

## 미차용 (토이 부적합)

JPA 전용·사내 인프라(이슈트래커/API 컬렉션/사내 배포 파이프라인) 결합이 강한 skill 들은
unigate(R2DBC·로컬 직접 배포)에는 부적합하여 제외했다.
