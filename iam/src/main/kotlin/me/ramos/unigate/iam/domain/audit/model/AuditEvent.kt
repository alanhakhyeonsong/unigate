package me.ramos.unigate.iam.domain.audit.model

import me.ramos.unigate.iam.domain.audit.enums.AuditEventType

/**
 * IAM 감사 이벤트 — **누가 · 무엇에 · 무슨 일을** 했는가.
 *
 * ## `actor` 와 `target` 을 지금부터 나누는 이유
 * 현재 유스케이스는 전부 자기 것만 다루므로 둘이 항상 같다. 그래도 나눠 둔다.
 *
 * Phase 9 에서 관리 API(`/iam/admin`)가 생기면 **관리자가 남의 프로필을 바꾸는** 사건이 나오고,
 * 그때 컬럼을 추가하면 **그 이전 기록은 영영 null** 이다. 감사는 되돌려 채울 수 없는 이력이라
 * "나중에 필요해지면 추가" 가 통하지 않는 몇 안 되는 영역이다.
 *
 * ## `traceId` 가 여기 없는 이유 (게이트웨이와 다른 선택)
 * GW 의 `AuditEvent` 에는 traceId 가 있다. 거기서는 감사 이벤트를 **경계(인증 핸들러)에서** 만들고,
 * 그 자리가 곧 traceId 를 읽을 수 있는 유일한 지점이었기 때문이다(reactive 컨텍스트 제약 —
 * `docs/learning/13` §5).
 *
 * IAM 은 감사 이벤트를 **유스케이스 안쪽**에서 만든다. 그래서 traceId 를 여기 두려면 모든 업무
 * Command(`UpdateMyProfileCommand` 등)에 traceId 필드를 끼워야 하는데, 그건 관측 관심사로
 * 도메인 입력을 오염시키는 것이다.
 *
 * 대신 **저장 어댑터가 찍는다.** traceId 는 "이 기록이 어느 요청에서 나왔는가" 라는 저장 시점의
 * 맥락이지 도메인 사실이 아니다. Servlet + ThreadLocal 이라 어댑터에서 읽는 것이 안전하다는 점도
 * reactive 인 GW 와 다르다.
 *
 * ## `detail`
 * 이벤트별 부가 맥락. JSON 직렬화는 어댑터가 한다 — 도메인은 저장 형식을 모른다.
 * ⚠️ **민감정보를 넣지 않는다**(`CLAUDE.md` §8). 감사 기록은 오래 남고 열람 범위가 넓다.
 */
data class AuditEvent(
  val type: AuditEventType,
  /** 행위자 — 요청을 일으킨 주체. 워커가 일으킨 사건이면 null(사람이 아니다). */
  val actorRef: String? = null,
  /** 대상 — 변경된 자원의 주인. 자기 것을 다루는 지금은 [actorRef] 와 같다. */
  val targetRef: String? = null,
  /** 대상 식별에 `userRef` 를 쓸 수 없을 때(신원 생성 전)의 보조 키. */
  val targetEmail: String? = null,
  /**
   * 사건이 일어난 **테넌트 맥락**. 테넌트와 무관한 사건(가입·자기 프로필 수정)은 null.
   *
   * `actorRef`/`targetRef` 와 **같은 이유로 미리 둔다** — 감사는 소급해 채울 수 없다.
   * 지금은 채우는 곳이 없고(테넌트 유스케이스가 P9c/P9d), 그래도 지금 두는 것이 맞다.
   *
   * `TenantId` VO 가 아니라 `String?` 인 것은 [actorRef]·[targetRef] 와 맞춘 것이다.
   * 감사는 **일어난 사실의 기록**이라, 나중에 VO 의 검증 규칙이 바뀌어도 과거 기록이
   * 되읽히지 않아야 한다. 도메인 불변식을 강제할 자리는 여기가 아니다.
   */
  val tenantRef: String? = null,
  /** 실패 사건의 분류. 성공 사건에는 null. */
  val reasonCode: String? = null,
  val detail: Map<String, Any?>? = null,
)
