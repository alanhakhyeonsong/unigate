// 런타임 설정 자리표시자 — **dev 전용 기본값**이다.
//
// 배포 컨테이너에서는 이 파일이 서빙되지 않는다. nginx 가 `/config.js` 를
// `/tmp/config.js`(entrypoint 가 envsubst 로 생성)로 alias 하기 때문이다.
// 여기 빈 값을 두는 이유는 dev 와 배포의 **코드 경로를 같게** 만들기 위해서다 —
// `window.__UNIGATE_CONFIG__` 가 없을 때만 동작하는 분기를 따로 두면 그 분기는 검증되지 않는다.
//
// dev 에서 API base 를 바꾸려면 이 파일이 아니라 `.env.development` / `.env.alpha` 를 고친다
// (빈 문자열은 `src/api/env.ts` 에서 빌드타임 값으로 폴백된다).
window.__UNIGATE_CONFIG__ = { apiBaseUrl: '' }
