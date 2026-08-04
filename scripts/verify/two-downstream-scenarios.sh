#!/usr/bin/env bash
#
# 다운스트림이 **둘일 때만** 재현되는 것들 — 재연 스크립트
#
#   S0   라우트가 제품별로 갈리는가
#   S1   공유 audience — 한 토큰의 aud 에 두 다운스트림이 함께 실린다
#   S2   claim 누출 — 요청 스코프보다 소속 목록이 넓다
#   S1b  토큰 재생 — demo 가 받은 Bearer 가 billing 에서도 통한다 (+ 위조 토큰 대조군)
#   S3   교차 테넌트 — 같은 자원, 판정 근거만 달라 200 과 403 이 갈린다
#   S3b  게이트 자체는 정상인가
#
# 상세: docs/learning/43 · 44 · docs/ALPHA_CONSOLE_SCENARIOS.md §6
#
# ── 사용법 ────────────────────────────────────────────────────────────────────
#   로컬:  scripts/verify/two-downstream-scenarios.sh --env local
#            사전: GW(8080) · demo(8081) · billing(8082) 기동, docker compose up -d
#            로그인은 스크립트가 한다(로컬 realm 은 테스트 계정이 있다).
#
#   alpha: SESSION=<쿠키값> scripts/verify/two-downstream-scenarios.sh --env alpha \
#            --tenant-a <테넌트1> --tenant-b <테넌트2>
#            사전: 브라우저로 콘솔 로그인 → DevTools → Cookies → SESSION 복사
#
# ── 왜 alpha 는 세션 쿠키를 받아야 하나 ───────────────────────────────────────
# alpha realm 은 **Direct access grants OFF** 이고 `setup-realm.sh` 가 테스트 계정을
# 만들지 않는다. 즉 토큰은 브라우저 로그인으로만 나온다.
# **자동화가 여기서 멈추는 것은 고장이 아니라 설계다**(ALPHA_CONSOLE_SCENARIOS §0.2).
#
# ⚠️ 실제 좌표는 이 파일에 없다. alpha 값은 `deploy/env/alpha.coord.env` 에서 읽는다.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

ENV_NAME=""
TENANT_A=""
TENANT_B=""
PF_PID=""

usage() {
  sed -n '2,30p' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)      ENV_NAME="${2:?--env 값이 필요합니다 (local|alpha)}"; shift 2 ;;
    --tenant-a) TENANT_A="${2:-}"; shift 2 ;;
    --tenant-b) TENANT_B="${2:-}"; shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "❌ $*" >&2; exit 1; }
hr()  { printf '\n──────── %s ────────\n' "$1"; }

cleanup() {
  [[ -n "${PF_PID}" ]] && kill "${PF_PID}" 2>/dev/null
  [[ -n "${JAR:-}" ]] && rm -f "${JAR}"
}
trap cleanup EXIT

UA='Mozilla/5.0 unigate-scenario'
JAR="$(mktemp)"

# ── 환경별 준비 ───────────────────────────────────────────────────────────────
case "${ENV_NAME}" in
  local)
    # 기본값은 `downstream-billing/application.yml` 의 픽스처 기본값과 같아야 한다.
    # 다르면 S3 이 403 으로 끝나고 **막힌 것처럼 보인다**(아래 §판정 기준 참조).
    TENANT_A="${TENANT_A:-acme}"
    TENANT_B="${TENANT_B:-globex}"
    GW="http://localhost:8080"
    BILLING_DIRECT="http://localhost:8082"

    [[ -f ./keycloak.secret.env ]] || die "keycloak.secret.env 가 없습니다."
    set -a; source ./keycloak.secret.env; set +a
    : "${TEST_USER_PASSWORD:?keycloak.secret.env 에 TEST_USER_PASSWORD 가 필요합니다}"

    hr "0. 로그인 (로컬 realm 의 테스트 계정)"
    AUTH_HTML=$(curl -s -c "$JAR" -b "$JAR" -L -A "$UA" "$GW/oauth2/authorization/keycloak")
    ACTION=$(printf '%s' "$AUTH_HTML" \
      | grep -oE '<form[^>]*id="kc-form-login"[^>]*>' \
      | grep -oE 'action="[^"]+"' | head -1 | sed 's/^action="//; s/"$//' | sed 's/&amp;/\&/g')
    [[ -n "$ACTION" ]] || die "로그인 폼을 찾지 못했습니다. GW 가 떠 있는지 확인하세요."
    curl -s -c "$JAR" -b "$JAR" -L -A "$UA" -o /dev/null \
      --data-urlencode "username=${SCENARIO_USER:-alice}" \
      --data-urlencode "password=${TEST_USER_PASSWORD}" "$ACTION"
    grep -qi 'SESSION' "$JAR" || die "세션 쿠키가 생기지 않았습니다(자격증명·계정 상태 확인)."
    echo "세션 획득"
    ;;

  alpha)
    : "${SESSION:?SESSION 쿠키 값이 필요합니다 — 브라우저 DevTools 에서 복사하세요}"
    [[ -n "${TENANT_A}" && -n "${TENANT_B}" ]] \
      || die "--tenant-a / --tenant-b 가 필요합니다. **기본값을 두지 않는다** — 실제 테넌트 이름은 환경마다 다르고, 틀린 값은 '통과'로 위장한다."
    [[ -f deploy/env/alpha.coord.env ]] || die "deploy/env/alpha.coord.env 가 없습니다."
    set -a; source deploy/env/alpha.coord.env; set +a
    GW="https://${UNIGATE_GATEWAY_HOST}"

    # billing 은 ingress 가 없다(의도적). 클러스터 안 Service 에 port-forward 로 붙는다 —
    # 이 경로가 곧 "GW 우회 직접 호출" 이고 S1b 재생이 성립하는 자리다.
    BILLING_DIRECT="http://localhost:18082"
    kubectl -n "${UNIGATE_NAMESPACE}" port-forward svc/unigate-demo-billing-svc 18082:80 \
      >/dev/null 2>&1 &
    PF_PID=$!
    for _ in $(seq 1 20); do
      curl -s --max-time 2 -o /dev/null "${BILLING_DIRECT}/public/ping" && break
      sleep 1
    done

    printf '#HttpOnly_%s\tFALSE\t/\tTRUE\t0\tSESSION\t%s\n' \
      "${UNIGATE_GATEWAY_HOST}" "${SESSION}" > "$JAR"
    ;;

  *) die "--env 는 local 또는 alpha 여야 합니다." ;;
esac

g()     { curl -s -b "$JAR" -A "$UA" "$@"; }
gcode() { curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -A "$UA" "$@"; }

hr "세션 확인"
code=$(gcode -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/echo")
echo "GET /api/echo -> HTTP ${code}"
[[ "$code" == "200" ]] || die "세션이 유효하지 않습니다."

hr "S0. 라우트가 제품별로 갈리는가"
echo -n "GET /api/billing/token -> "
g -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/token" | jq -c '{service, requestedTenant}'

hr "S1+S2. 공유 aud · claim 누출"
g -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/token" \
  | jq '{audience, tenantMemberships, requestedTenant}'

hr "S1b. demo 가 받은 Bearer 를 billing 에 재생 (GW 우회)"
RELAYED=$(g -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/echo" \
  | jq -r '.headers | to_entries[] | select(.key|ascii_downcase=="authorization") | .value')
if [[ -z "$RELAYED" || "$RELAYED" == "null" ]]; then
  echo "  relay 된 Authorization 을 못 얻었다 (세션 만료?)"
else
  # 토큰 원문은 남기지 않는다(CLAUDE.md §8). 길이와 aud 만 본다.
  echo "  relay 토큰: 앞 19자 ${RELAYED:0:19}…(마스킹) · 길이 ${#RELAYED}"
  echo -n "  그 토큰의 aud: "
  printf '%s' "${RELAYED#Bearer }" | cut -d. -f2 \
    | python3 -c 'import sys,base64,json; p=sys.stdin.read().strip(); print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))).get("aud")))'
  echo -n "  billing 직접 호출        -> HTTP "
  curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: ${RELAYED}" -H "X-Tenant-Id: ${TENANT_A}" "${BILLING_DIRECT}/token"
  # 대조군이 없으면 "검증을 안 해서 통과한 것" 이라는 반박이 닫히지 않는다.
  echo -n "  위조 Bearer (대조군)     -> HTTP "
  curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer forged.token.value" -H "X-Tenant-Id: ${TENANT_A}" "${BILLING_DIRECT}/token"
fi

hr "S3. 교차 테넌트 — 같은 자원, 판정 근거만 다르다"
printf 'GET /api/billing/subscriptions/sub-b-1        (토큰 소속 목록) -> HTTP %s  ' \
  "$(gcode -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/subscriptions/sub-b-1")"
g -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/subscriptions/sub-b-1" \
  | jq -c '{id,tenantId,verdictBy}' 2>/dev/null
printf 'GET /api/billing/scoped/subscriptions/sub-b-1 (검증된 스코프)  -> HTTP %s\n' \
  "$(gcode -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/scoped/subscriptions/sub-b-1")"
printf 'GET /api/billing/scoped/subscriptions/sub-a-1 (대조군)         -> HTTP %s  ' \
  "$(gcode -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/scoped/subscriptions/sub-a-1")"
g -H "X-Requested-Tenant: ${TENANT_A}" "$GW/api/billing/scoped/subscriptions/sub-a-1" \
  | jq -c '{id,tenantId,verdictBy}' 2>/dev/null

hr "S3b. GW 게이트 자체"
printf 'X-Requested-Tenant: nonmember -> HTTP %s\n' \
  "$(gcode -H 'X-Requested-Tenant: nonmember' "$GW/api/billing/subscriptions/sub-b-1")"

hr "판정 기준"
cat <<'EOF'
  S1   aud 에 두 다운스트림 audience 가 **둘 다**            → 공유 aud 재현
  S2   tenantMemberships 가 requestedTenant 보다 넓다        → claim 누출 재현
  S1b  재생 200 **이면서** 위조 401                          → 검증은 도는데 통과한다(핵심)
  S3   취약 200 · scoped 403 · scoped(내 테넌트) 200         → 교차 테넌트 구멍 재현
  S3b  403                                                   → 게이트는 정상

  ⚠️ S3 의 취약 엔드포인트가 403 이면 **구멍이 막힌 게 아닐 수 있다.**
     픽스처 테넌트가 realm 에 없거나 계정이 한쪽에만 속하면 같은 403 이 나온다 —
     재현 조건 불충족이 "통과"로 위장하는 자리다. S2 의 tenantMemberships 에
     --tenant-b 값이 있는지 **먼저** 확인한다.
EOF
