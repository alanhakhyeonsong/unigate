-- unigate 초기 스키마: 감사 로그
-- 사내 표준 인증 프레임워크 승격 요건(§7 감사 로그)의 최소 골격.
CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,          -- LOGIN_SUCCESS / LOGIN_FAILURE / TOKEN_ISSUED ...
    subject      VARCHAR(255),                   -- Keycloak sub
    client_id    VARCHAR(128),
    audience     VARCHAR(128),
    reason_code  VARCHAR(64),                    -- token_expired / invalid_audience / rate_limited ...
    trace_id     VARCHAR(64),                    -- 분산 트레이싱 상관관계
    detail       JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_subject   ON audit_log (subject);
CREATE INDEX IF NOT EXISTS idx_audit_log_event     ON audit_log (event_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_created   ON audit_log (created_at);
