-- 이메일 변경 유스케이스: 반영 대기 중인 이메일.
--
-- 확정 값(email)과 요청 값을 따로 두는 이유는 도메인 KDoc(UserProfile)에 있다. 요약하면 —
-- email 의 SoT 는 Keycloak 이고 IAM 의 값은 사본이라, 요청 즉시 사본을 덮어쓰면 반영 전 구간에서
-- 화면과 로그인이 어긋나고 실패 시 값이 조용히 되돌아간다.
--
-- UNIQUE 를 걸지 않는다:
--   두 사람이 같은 주소로 동시에 변경을 요청하는 것 자체는 막을 이유가 없다. 실제 충돌 판정의
--   SoT 는 Keycloak 이고, 먼저 반영된 쪽이 이기고 나중 쪽은 영구 실패 → 보상으로 정리된다.
--   여기에 UNIQUE 를 걸면 "아직 아무것도 확정되지 않은 요청" 끼리 서로를 막는다.
ALTER TABLE user_profile
    ADD COLUMN pending_email VARCHAR(255);

COMMENT ON COLUMN user_profile.pending_email IS '반영 대기 중인 이메일. NULL 이면 진행 중인 변경 없음';
