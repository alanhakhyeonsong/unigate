-- IAM 감사 로그 (Phase 8g)
--
-- ⚠️ 게이트웨이의 `audit_log`(unigate DB)와 **이름은 같지만 다른 테이블**이다.
-- 두 스트림을 합치지 않기로 했다:
--   * GW 는 R2DBC(논블로킹), IAM 은 JPA(블로킹)로 스택이 반대라 한 테이블을 공유하면 스키마 변경이
--     두 모듈을 묶는다.
--   * DB 자체가 이미 분리돼 있다(unigate / unigate_iam). 합치려면 IAM 이 두 번째 datasource 를
--     들어야 하고, 그건 "IAM 은 자기 DB 만 안다" 는 경계를 깬다.
-- 상관관계는 **trace_id 로 잇는다** — 같은 요청에서 나온 두 DB 의 기록이 같은 trace_id 를 갖는다.
--
-- 담는 것은 **도메인 변경 사건**이다(가입·신원연결·프로필·동의). 인증 사건(로그인/로그아웃)은
-- 게이트웨이 소관이고, **조회는 남기지 않는다** — 남기면 액세스 로그가 되어 정작 중요한 변경이 묻힌다.
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(64)  NOT NULL,        -- USER_REGISTERED / PROFILE_UPDATED / ...
    -- 행위자와 대상을 **지금부터** 나눈다. 현재는 항상 같지만, Phase 9 의 관리 API 가 생긴 뒤에
    -- 컬럼을 추가하면 그 이전 기록은 영영 null 이다. 감사는 소급해 채울 수 없다.
    actor_ref     VARCHAR(64),                  -- 요청을 일으킨 주체(JWT sub). 워커 사건이면 NULL
    target_ref    VARCHAR(64),                  -- 변경된 자원의 주인
    -- 신원 연결 **전**(PENDING_IDENTITY)에는 user_ref 가 없어 대상을 가리킬 수 없다.
    -- 가입·신원생성 실패 사건이 그 구간에 있으므로 이메일을 보조 키로 둔다.
    target_email  VARCHAR(255),
    reason_code   VARCHAR(64),                  -- identity_already_exists / consent_version_mismatch ...
    trace_id      VARCHAR(64),                  -- GW 감사와 잇는 유일한 열쇠
    detail        JSONB,                        -- 변경 전/후 등. 민감정보 금지(CLAUDE.md §8)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- "이 사용자에게 무슨 일이 있었나" — 가장 흔한 조회.
CREATE INDEX IF NOT EXISTS idx_audit_log_target  ON audit_log (target_ref);
-- 신원 연결 전 사건은 target_ref 가 NULL 이라 위 인덱스로 못 찾는다.
CREATE INDEX IF NOT EXISTS idx_audit_log_email   ON audit_log (target_email);
-- "이 요청에서 무슨 일이 있었나" — GW 감사와 조인할 때 쓴다.
CREATE INDEX IF NOT EXISTS idx_audit_log_trace   ON audit_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log (created_at);
