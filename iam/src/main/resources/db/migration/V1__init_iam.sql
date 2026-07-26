-- IAM 초기 스키마 (Phase 8d)
--
-- 두 테이블이 **한 트랜잭션에 함께 쓰인다**는 것이 outbox 패턴의 핵심이다.
-- 가입 요청은 user_profile + outbox_record 를 동시에 커밋하고, Keycloak 반영은 워커가 나중에 한다.
-- 그래야 "프로필은 저장됐는데 Keycloak 생성 지시는 유실" 되는 경우가 없다.

-- ---------------------------------------------------------------------------
-- user_profile — 앱 고유 사용자 프로필
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_profile (
    id                  BIGSERIAL PRIMARY KEY,
    -- 가입 입력값. ⚠️ SoT 는 Keycloak 이다 — 여기 unique 는 **1차 방어**일 뿐,
    -- 최종 중복 판정은 Keycloak 이 한다(외부에서 만들어진 사용자는 IAM DB 에 없다).
    email               VARCHAR(255) NOT NULL UNIQUE,
    -- Keycloak 사용자 참조. **PENDING_IDENTITY 동안 NULL 이다**(outbox 라 아직 안 만들어졌다).
    -- 이 nullable 이 outbox 선택의 직접적 결과다.
    user_ref            VARCHAR(64) UNIQUE,
    onboarding_state    VARCHAR(32)  NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    locale              VARCHAR(16)  NOT NULL,
    -- ConsentRecord VO 를 평탄화. 버전 없이 boolean 만 두면 약관 개정 시 의미가 없어진다.
    consent_tos_version VARCHAR(32),
    consent_accepted_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_profile_state ON user_profile (onboarding_state);

-- ---------------------------------------------------------------------------
-- outbox_record — 외부 시스템(Keycloak) 반영 지시
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox_record (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(64)  NOT NULL,          -- CREATE_KEYCLOAK_USER ...
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL,          -- PENDING / COMPLETED / DEAD
    attempts        INT          NOT NULL DEFAULT 0,
    -- 지수 백오프. 실패한 레코드가 즉시 재시도되어 외부 시스템을 두드리는 것을 막는다.
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 워커의 클레임 쿼리 전용 **부분 인덱스**.
--
-- 워커는 `WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at
-- FOR UPDATE SKIP LOCKED LIMIT 1` 로 집는다. 처리 끝난 레코드(COMPLETED/DEAD)가 쌓여도
-- 인덱스는 PENDING 만 담으므로 **처리량이 늘어도 클레임 비용이 커지지 않는다.**
--
-- 다중 인스턴스에서 이 인덱스와 SKIP LOCKED 조합이 핵심이다. 여러 워커가 동시에 폴링해도
-- 서로 다른 행을 잡아 락 대기 없이 병렬 처리된다.
CREATE INDEX IF NOT EXISTS idx_outbox_claim
    ON outbox_record (next_attempt_at)
    WHERE status = 'PENDING';

-- 운영 조회용 — 죽은 레코드를 찾아 수동 개입할 때.
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_record (status);
