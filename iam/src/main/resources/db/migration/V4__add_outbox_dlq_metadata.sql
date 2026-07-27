-- outbox DLQ 메타데이터 (Phase 9b)
--
-- 기존에는 실패 정보가 `last_error` TEXT 한 칸뿐이었다. 레코드가 DEAD 로 죽어 있어도
-- **언제 죽었는지, 무엇이 터졌는지**를 알 수 없어 사후 조사가 추측이 된다.
--
-- 메시지 브로커 기반 DLQ 들이 이관 시 함께 남기는 것 — reason / at / delivery-count /
-- last-exception-class — 을 컬럼으로 가져온다. 저장소는 브로커가 아니라 DB 그대로다
-- (상세 판단은 docs/plans 의 "DLQ 설계 확정" 참조).
ALTER TABLE outbox_record ADD COLUMN IF NOT EXISTS dead_at              TIMESTAMPTZ;
ALTER TABLE outbox_record ADD COLUMN IF NOT EXISTS last_exception_class VARCHAR(255);

COMMENT ON COLUMN outbox_record.dead_at IS
    'DEAD 전이 시각. updated_at 으로 갈음하지 않는 이유는 죽은 뒤에도 레코드가 갱신될 수 있어(운영자 재처리 등) "언제 죽었나" 가 덮이기 때문이다.';
COMMENT ON COLUMN outbox_record.last_exception_class IS
    '마지막 실패의 예외 FQCN. 메시지가 아니라 클래스명만 담는다 — 메시지에는 외부 응답 본문이 섞여 토큰·secret 이 들어올 수 있다(CLAUDE.md 8).';

-- 운영 조회: "죽은 것들을 최근 순으로". 기존 idx_outbox_status 는 상태만 보므로 정렬이 따로 붙는다.
-- DEAD 만 담는 **부분 인덱스**라 정상 운영(대부분 COMPLETED)에서는 거의 비어 있어 비용이 없다.
CREATE INDEX IF NOT EXISTS idx_outbox_dead_at
    ON outbox_record (dead_at DESC)
    WHERE status = 'DEAD';

-- COMPLETED 정리(retention)용. 워커가 주기적으로 오래된 완료 레코드를 지운다.
-- 이 인덱스가 없으면 정리 쿼리가 테이블 전체를 훑는다 — 레코드가 쌓일수록 느려지고,
-- 정리가 느려지면 더 쌓이는 악순환이 된다.
CREATE INDEX IF NOT EXISTS idx_outbox_completed_updated
    ON outbox_record (updated_at)
    WHERE status = 'COMPLETED';
