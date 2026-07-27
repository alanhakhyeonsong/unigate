-- 테넌트 · 멤버십 (Phase 9c-2)
--
-- P8b 에서 도메인 모델만 만들어 두고 스키마는 미뤘다. 소비자(CreateTenant 유스케이스)가
-- 생기는 지금 만든다 — 감사 컬럼(V3)과 달리 이쪽은 **늦게 만들어도 잃는 데이터가 없어서**
-- 유스케이스와 함께 오는 편이 맞다.

-- ---------------------------------------------------------------------------
-- tenant
-- ---------------------------------------------------------------------------
-- id 가 **자연키**다. TenantId 는 사용자가 정하는 slug 이고(`^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$`),
-- Keycloak group 경로 `/tenants/{id}` 와 1:1 로 대응한다. 대리키를 두면 "DB 의 id" 와
-- "group 경로의 id" 가 갈라져, 둘을 잇는 코드가 계속 따라다닌다.
--
-- 그 대가로 **id 는 변경 불가**가 된다. 테넌트 이름을 바꾸고 싶으면 display_name 을 바꾼다 —
-- 식별자와 표시 이름을 나눈 이유가 이것이다.
CREATE TABLE IF NOT EXISTS tenant (
    id            VARCHAR(64)  PRIMARY KEY,
    display_name  VARCHAR(255) NOT NULL,
    -- PENDING / ACTIVE / SUSPENDED / ARCHIVED. PENDING 으로 시작해 외부 프로비저닝
    -- (Keycloak group 생성)이 끝나야 ACTIVE 가 된다.
    status        VARCHAR(16)  NOT NULL,
    -- 쿼터. NULL 은 **무제한**이다(0 이 아니다 — 0 은 "아무도 못 들어옴" 이라는 다른 뜻이다).
    max_users     INT,
    feature_flags JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- "프로비저닝이 안 끝난 테넌트" 를 찾는 운영 조회. 정상 상태에서는 거의 비어 있다.
CREATE INDEX IF NOT EXISTS idx_tenant_status ON tenant (status);

-- ---------------------------------------------------------------------------
-- membership — user ↔ tenant 다대다
-- ---------------------------------------------------------------------------
-- Keycloak 의 group 소속은 단일·평면이라 이 관계를 표현하지 못한다. 한 사용자가 N 테넌트에
-- 각기 다른 역할·초대 상태로 속하는 것이 IAM 이 소유하는 도메인이다
-- (IAM_PLATFORM_DECISION.md §6.1).
CREATE TABLE IF NOT EXISTS membership (
    id         BIGSERIAL    PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL REFERENCES tenant (id),
    -- Keycloak sub. user_profile 이 아니라 **UserRef 로 참조**한다 — 신원 연결 전 사용자를
    -- 초대할 수 있어야 하고, FK 를 걸면 그 경우가 막힌다.
    user_ref   VARCHAR(64)  NOT NULL,
    role       VARCHAR(32)  NOT NULL,
    -- INVITED / ACTIVE / REVOKED
    status     VARCHAR(16)  NOT NULL,
    invited_by VARCHAR(64),
    invited_at TIMESTAMPTZ  NOT NULL,
    joined_at  TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ⚠️ **부분 unique** 다 — `Membership` KDoc 이 "스키마 설계 시점의 숙제" 로 남겨둔 지점.
--
-- 단순 UNIQUE(tenant_id, user_ref) 로 걸면 한 번 탈퇴(REVOKED)한 사용자가 **영영 재가입할 수
-- 없다.** 그렇다고 제약을 빼면 같은 사람이 활성 멤버십을 두 개 갖는 상태가 만들어진다.
--
-- REVOKED 를 제외하면 둘 다 풀린다: 활성/초대 중복은 막고, 지난 이력은 얼마든지 쌓인다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_active
    ON membership (tenant_id, user_ref)
    WHERE status <> 'REVOKED';

-- "이 테넌트에 누가 있나" — 멤버 목록·쿼터 계산의 기본 조회.
CREATE INDEX IF NOT EXISTS idx_membership_tenant ON membership (tenant_id, status);
-- "이 사용자는 어느 테넌트에 속하나" — 토큰 claim 발행(P9e)과 GW 게이트가 쓸 방향이다.
CREATE INDEX IF NOT EXISTS idx_membership_user ON membership (user_ref, status);
