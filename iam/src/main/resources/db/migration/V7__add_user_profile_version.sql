-- 프로필 낙관적 락 (Phase 8 미해결 6번)
--
-- 지금까지 user_profile 에는 동시 수정 방어가 없었다. 두 요청이 같은 프로필을 읽고 각자 고치면
-- **나중 쓰기가 먼저 쓰기를 조용히 덮는다**(lost update). 에러도 로그도 남지 않아, 사용자는
-- "분명 바꿨는데 안 바뀌어 있다" 만 겪는다.
--
-- 이 컬럼이 특히 필요한 곳은 이메일 변경이다. 도메인이 "진행 중인 변경이 있으면 거절" 하지만
-- (UserProfile.requestEmailChange), 그 검사는 **읽은 시점의 값**을 본다. 두 요청이 동시에
-- pendingEmail=null 을 읽으면 둘 다 통과하고 outbox 지시가 두 개 만들어진다. 도메인이 막으려던
-- 바로 그 상태가 경합으로 뚫린다 — 그 최종 방어선이 여기다.
--
-- ## 왜 비관적 락(SELECT FOR UPDATE)이 아닌가
-- 프로필 수정은 충돌이 드물다(대개 본인 1명 + 워커). 드문 충돌에 매 요청 행 잠금을 거는 것은
-- 비싸고, 워커가 외부(Keycloak) 호출을 트랜잭션 안에서 하므로 그 사이 행이 잠겨 있으면
-- 사용자 요청이 Keycloak 응답 시간만큼 대기하게 된다. outbox_record 의 SKIP LOCKED 와는
-- 상황이 다르다 — 그쪽은 경합이 **설계상 상시**라 잠금이 맞다.
--
-- ## DEFAULT 0 인 이유
-- 기존 행에도 값이 있어야 NOT NULL 을 걸 수 있다. Hibernate 는 이 값을 읽어 UPDATE 의
-- WHERE 절에 넣고 1 씩 올린다. 시작값이 무엇이든 상관없고, 0 이 "아직 한 번도 안 바뀜" 으로 읽힌다.
ALTER TABLE user_profile
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN user_profile.version IS 'JPA 낙관적 락 버전. UPDATE 시 WHERE 절에 실려 lost update 를 막는다';
