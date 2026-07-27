-- 감사 로그에 tenant 축 추가 (Phase 9a)
--
-- ⚠️ **테넌트 기능보다 이 컬럼이 먼저 온다.** 순서가 뒤바뀐 것처럼 보이지만 의도적이다.
--
-- V2 가 actor_ref / target_ref 를 "현재는 항상 같지만" 미리 나눠 둔 것과 **같은 이유**다:
-- 감사는 **소급해 채울 수 없는 이력**이다. 테넌트 관리 API(P9c/P9d)가 먼저 돌기 시작하고
-- 그 뒤에 컬럼을 추가하면, 그 사이에 일어난 사건들은 **영영 tenant 를 알 수 없다.**
--
-- 이것이 "소비자 없는 추상화를 만들지 않는다"(Phase 5 재정의)와 충돌하지 않는 이유:
-- 포트·엔티티는 나중에 만들어도 **잃는 것이 없지만**, 감사 컬럼은 늦으면 데이터를 잃는다.
-- 그래서 tenant/membership 테이블은 유스케이스와 함께 만들고, 이 컬럼만 앞당긴다.
--
-- 지금은 모든 기록이 NULL 이다(테넌트 개념의 소비자가 아직 없다). 정상이며,
-- P9d 의 멤버십 유스케이스가 첫 실채움 지점이 된다.
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS tenant_ref VARCHAR(64);

-- 조회 형태에 맞춘 **복합** 인덱스다. tenant_ref 단독 인덱스를 만들지 않은 이유:
-- 테넌트 수는 적고 행은 많아 카디널리티가 낮다 → 단독으로는 플래너가 잘 쓰지 않는다.
-- 실제 조회는 거의 항상 "이 테넌트에서 **최근** 무슨 일이 있었나" 라 시간 정렬이 따라붙는다.
-- created_at DESC 를 인덱스에 포함하면 정렬을 위한 별도 sort 가 사라진다.
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_created
    ON audit_log (tenant_ref, created_at DESC);
