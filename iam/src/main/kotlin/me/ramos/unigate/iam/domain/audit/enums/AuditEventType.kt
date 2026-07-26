package me.ramos.unigate.iam.domain.audit.enums

/**
 * IAM 감사 이벤트 종류 — **도메인에서 무슨 일이 일어났는가**.
 *
 * ## 게이트웨이의 것과 왜 별개인가
 * `gateway` 에도 같은 이름의 enum 이 있지만 담는 사건이 다르다.
 *
 * | | gateway | iam (여기) |
 * |---|---|---|
 * | 무엇 | **인증 사건** (LOGIN_SUCCESS / LOGOUT) | **도메인 변경** (가입·프로필·동의) |
 * | 언제 | 세션이 생기고 사라질 때 | 데이터가 바뀔 때 |
 * | 어디 | `unigate` DB | `unigate_iam` DB |
 *
 * 두 모듈이 공유 모듈 없이 각자 정의한다. 억지로 합치면 인증 사건 하나가 늘 때마다 IAM 도 다시
 * 빌드해야 하고, 그건 모듈을 나눈 이유를 무너뜨린다. 상관관계는 **`traceId` 로 잇는다**.
 *
 * ## 여기 없는 것 — 조회
 * 조회는 남기지 않는다. 프로필 조회까지 기록하면 감사 테이블이 사실상 액세스 로그가 되고,
 * **정작 중요한 변경 사건이 그 안에 묻힌다.** 조회 추적이 필요해지면 별도 스트림으로 다룬다.
 */
enum class AuditEventType {
  /** 가입 요청이 접수되어 프로필이 만들어졌다. **아직 Keycloak 신원은 없다**(outbox 대기). */
  USER_REGISTERED,

  /** outbox 워커가 Keycloak 사용자 생성에 성공해 신원이 연결됐다. */
  IDENTITY_CREATED,

  /**
   * 신원 생성이 **영구 실패**했다(이메일 중복 등). 재시도 실패는 여기 포함하지 않는다 —
   * 그건 아직 진행 중인 상태이지 확정된 사건이 아니다.
   */
  IDENTITY_CREATION_FAILED,

  /** 프로필 필드가 변경됐다. `detail` 에 **변경 전/후**를 담는다. */
  PROFILE_UPDATED,

  /** 약관에 동의했다. `detail` 에 버전을 담는다 — 법적 근거가 되는 기록이다. */
  CONSENT_ACCEPTED,
}
