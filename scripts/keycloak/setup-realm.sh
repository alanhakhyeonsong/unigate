#!/usr/bin/env bash
#
# unigate — Keycloak realm 사전 구성 스크립트 (멱등)
#
# 문서: docs/KEYCLOAK_REALM_SETUP.md
#
# Keycloak Admin REST API 를 직접 호출한다 (curl + jq 만 필요, kcadm.sh 설치 불필요).
# 관리자 비밀번호는 파일에 저장하지 않는다 — 환경변수 또는 대화형 입력으로만 받는다.
#
# 사용법:
#   export KEYCLOAK_URL="https://<keycloak-host>"
#   scripts/keycloak/setup-realm.sh --env local
#   scripts/keycloak/setup-realm.sh --env alpha --alpha-host <alpha-ingress-host>
#   scripts/keycloak/setup-realm.sh --env local --dry-run
#
# 주의: 실제 호스트명·계정·secret 은 이 파일에 하드코딩하지 않는다.
#       전부 환경변수 또는 인자로 주입한다.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# 상수
# ---------------------------------------------------------------------------
readonly ADMIN_REALM="master"
readonly ADMIN_CLIENT_ID="admin-cli"

readonly GATEWAY_CLIENT_ID="unigate-client"
readonly DOWNSTREAM_CLIENT_ID="unigate-downstream-demo"
readonly AUDIENCE_MAPPER_NAME="downstream-audience"

readonly ROLE_USER="unigate-user"
readonly ROLE_ADMIN="unigate-admin"
readonly GROUP_USERS="unigate-users"

# Realm 세션/토큰 정책 (docs/KEYCLOAK_REALM_SETUP.md §4.1)
readonly SSO_SESSION_IDLE_SECONDS=1800    # 30m — spring.session.timeout 과 정렬
readonly SSO_SESSION_MAX_SECONDS=36000    # 10h
readonly ACCESS_TOKEN_LIFESPAN_SECONDS=300 # 5m

# Spring Security 기본 콜백 경로: {baseUrl}/login/oauth2/code/{registrationId}
readonly OAUTH_CALLBACK_PATH="/login/oauth2/code/keycloak"

# 네트워크 타임아웃 — 무한 대기 방지
readonly CONNECT_TIMEOUT=10
readonly MAX_TIME=30

# ---------------------------------------------------------------------------
# 인자 파싱
# ---------------------------------------------------------------------------
TARGET_ENV=""
ALPHA_HOST=""
DRY_RUN="false"
KEYCLOAK_URL="${KEYCLOAK_URL:-}"

usage() {
  cat <<'EOF'
사용법: setup-realm.sh --env <local|alpha> [옵션]

옵션:
  --env <local|alpha>     대상 환경. local -> realm 'test', alpha -> realm 'unigate'
  --alpha-host <host>     alpha ingress 호스트 (--env alpha 일 때 필수)
  --keycloak-url <url>    Keycloak base URL (KEYCLOAK_URL 환경변수로도 지정 가능, 필수)
  --dry-run               변경 없이 수행할 작업만 출력
  -h, --help              도움말

환경변수:
  KEYCLOAK_URL             Keycloak base URL (예: https://keycloak.example.com)
  KEYCLOAK_ADMIN_USER      관리자 계정 (미설정 시 입력 요청)
  KEYCLOAK_ADMIN_PASSWORD  관리자 비밀번호 (미설정 시 입력 요청, 화면 미출력)
  TEST_USER_PASSWORD       테스트 사용자 비밀번호 (미설정 시 자동 생성 후 출력)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)          TARGET_ENV="${2:-}"; shift 2 ;;
    --alpha-host)   ALPHA_HOST="${2:-}"; shift 2 ;;
    --keycloak-url) KEYCLOAK_URL="${2:-}"; shift 2 ;;
    --dry-run)      DRY_RUN="true"; shift ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ -z "$KEYCLOAK_URL" ]]; then
  echo "오류: Keycloak base URL 이 필요합니다. KEYCLOAK_URL 환경변수 또는 --keycloak-url 로 지정하세요." >&2
  usage >&2
  exit 1
fi
KEYCLOAK_URL="${KEYCLOAK_URL%/}"   # 끝 슬래시 제거 — URL 조합 시 중복 방지

# ---------------------------------------------------------------------------
# 환경별 설정 결정
# ---------------------------------------------------------------------------
case "$TARGET_ENV" in
  local)
    REALM="test"
    CREATE_TEST_USERS="true"
    REDIRECT_URIS='["http://localhost:8080'"$OAUTH_CALLBACK_PATH"'","http://127.0.0.1:8080'"$OAUTH_CALLBACK_PATH"'"]'
    WEB_ORIGINS='["http://localhost:8080","http://127.0.0.1:8080"]'
    POST_LOGOUT_URIS="http://localhost:8080/##http://127.0.0.1:8080/"
    ;;
  alpha)
    REALM="unigate"
    CREATE_TEST_USERS="false"
    if [[ -z "$ALPHA_HOST" ]]; then
      echo "오류: --env alpha 에는 --alpha-host <ingress-host> 가 필요합니다." >&2
      exit 1
    fi
    REDIRECT_URIS='["https://'"$ALPHA_HOST$OAUTH_CALLBACK_PATH"'"]'
    WEB_ORIGINS='["https://'"$ALPHA_HOST"'"]'
    POST_LOGOUT_URIS="https://$ALPHA_HOST/"
    ;;
  *)
    echo "오류: --env 는 local 또는 alpha 여야 합니다." >&2
    usage >&2
    exit 1
    ;;
esac

readonly ISSUER_URI="$KEYCLOAK_URL/realms/$REALM"

# ---------------------------------------------------------------------------
# 유틸
# ---------------------------------------------------------------------------
log()  { printf '\033[0;34m[info]\033[0m  %s\n' "$*"; }
ok()   { printf '\033[0;32m[ok]\033[0m    %s\n' "$*"; }
warn() { printf '\033[0;33m[warn]\033[0m  %s\n' "$*"; }
die()  { printf '\033[0;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' 명령이 필요합니다. 설치 후 다시 실행하세요."
}
require_cmd curl
require_cmd jq

# api <METHOD> <PATH> [JSON_BODY]
# 성공 시 응답 바디를 stdout 으로, 실패 시 HTTP 코드와 함께 종료한다.
api() {
  local method="$1" path="$2" body="${3:-}"
  local url="$KEYCLOAK_URL$path"
  local response http_code

  if [[ -n "$body" ]]; then
    response=$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" \
      --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      -H "Content-Type: application/json" \
      --data "$body")
  else
    response=$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" \
      --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
      -H "Authorization: Bearer $ACCESS_TOKEN")
  fi

  http_code="${response##*$'\n'}"
  local payload="${response%$'\n'*}"

  if [[ "$http_code" -ge 400 ]]; then
    die "$method $path 실패 (HTTP $http_code): $payload"
  fi
  printf '%s' "$payload"
}

# 존재 여부 조회용 — 404 를 오류로 취급하지 않는다.
api_status() {
  local method="$1" path="$2"
  curl -sS -o /dev/null -w '%{http_code}' -X "$method" "$KEYCLOAK_URL$path" \
    --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
    -H "Authorization: Bearer $ACCESS_TOKEN"
}

step() {
  if [[ "$DRY_RUN" == "true" ]]; then
    log "(dry-run) $*"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 1) 관리자 인증
# ---------------------------------------------------------------------------
log "대상: env=$TARGET_ENV  realm=$REALM  keycloak=$KEYCLOAK_URL"
[[ "$DRY_RUN" == "true" ]] && warn "dry-run 모드 — 변경을 적용하지 않습니다."

ADMIN_USER="${KEYCLOAK_ADMIN_USER:-}"
if [[ -z "$ADMIN_USER" ]]; then
  read -r -p "Keycloak 관리자 계정: " ADMIN_USER
fi

ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-}"
if [[ -z "$ADMIN_PASSWORD" ]]; then
  read -r -s -p "Keycloak 관리자 비밀번호: " ADMIN_PASSWORD
  echo
fi
[[ -n "$ADMIN_PASSWORD" ]] || die "관리자 비밀번호가 비어 있습니다."

log "관리자 토큰 발급 중..."
TOKEN_RESPONSE=$(curl -sS -X POST \
  "$KEYCLOAK_URL/realms/$ADMIN_REALM/protocol/openid-connect/token" \
  --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=$ADMIN_CLIENT_ID" \
  --data-urlencode "username=$ADMIN_USER" \
  --data-urlencode "password=$ADMIN_PASSWORD")
unset ADMIN_PASSWORD

ACCESS_TOKEN=$(printf '%s' "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
[[ -n "$ACCESS_TOKEN" ]] || die "관리자 인증 실패: $(printf '%s' "$TOKEN_RESPONSE" | jq -r '.error_description // .error // .')"
ok "관리자 인증 완료"

# ---------------------------------------------------------------------------
# 2) Realm
# ---------------------------------------------------------------------------
realm_payload=$(jq -n \
  --arg realm "$REALM" \
  --argjson idle "$SSO_SESSION_IDLE_SECONDS" \
  --argjson max "$SSO_SESSION_MAX_SECONDS" \
  --argjson atl "$ACCESS_TOKEN_LIFESPAN_SECONDS" \
  '{
     realm: $realm,
     enabled: true,
     sslRequired: "external",
     registrationAllowed: false,
     verifyEmail: false,
     loginWithEmailAllowed: true,
     ssoSessionIdleTimeout: $idle,
     ssoSessionMaxLifespan: $max,
     accessTokenLifespan: $atl,
     revokeRefreshToken: false
   }')

if [[ "$(api_status GET "/admin/realms/$REALM")" == "200" ]]; then
  if step "realm '$REALM' 정책 갱신"; then
    api PUT "/admin/realms/$REALM" "$realm_payload" >/dev/null
    ok "realm '$REALM' 갱신"
  fi
else
  if step "realm '$REALM' 생성"; then
    api POST "/admin/realms" "$realm_payload" >/dev/null
    ok "realm '$REALM' 생성"
  fi
fi

# ---------------------------------------------------------------------------
# 3) Clients
# ---------------------------------------------------------------------------
# upsert_client <payload> -> client 의 내부 UUID 를 stdout 으로
upsert_client() {
  local client_id="$1" payload="$2" existing uuid
  existing=$(api GET "/admin/realms/$REALM/clients?clientId=$client_id")
  uuid=$(printf '%s' "$existing" | jq -r '.[0].id // empty')

  if [[ -n "$uuid" ]]; then
    api PUT "/admin/realms/$REALM/clients/$uuid" "$payload" >/dev/null
    ok "client '$client_id' 갱신" >&2
  else
    api POST "/admin/realms/$REALM/clients" "$payload" >/dev/null
    uuid=$(api GET "/admin/realms/$REALM/clients?clientId=$client_id" | jq -r '.[0].id')
    ok "client '$client_id' 생성" >&2
  fi
  printf '%s' "$uuid"
}

gateway_client_payload=$(jq -n \
  --arg clientId "$GATEWAY_CLIENT_ID" \
  --argjson redirectUris "$REDIRECT_URIS" \
  --argjson webOrigins "$WEB_ORIGINS" \
  --arg postLogout "$POST_LOGOUT_URIS" \
  '{
     clientId: $clientId,
     name: "unigate Gateway (BFF)",
     description: "Spring Cloud Gateway 인증 게이트웨이. Authorization Code Flow + TokenRelay.",
     enabled: true,
     protocol: "openid-connect",
     publicClient: false,
     standardFlowEnabled: true,
     implicitFlowEnabled: false,
     directAccessGrantsEnabled: false,
     serviceAccountsEnabled: true,
     authorizationServicesEnabled: false,
     fullScopeAllowed: true,
     redirectUris: $redirectUris,
     webOrigins: $webOrigins,
     attributes: {
       "pkce.code.challenge.method": "S256",
       "post.logout.redirect.uris": $postLogout
     }
   }')

downstream_client_payload=$(jq -n \
  --arg clientId "$DOWNSTREAM_CLIENT_ID" \
  '{
     clientId: $clientId,
     name: "unigate Downstream Demo (audience only)",
     description: "다운스트림 예시 앱. 로그인 흐름에 참여하지 않고 access token 의 aud 값으로만 사용된다.",
     enabled: true,
     protocol: "openid-connect",
     publicClient: false,
     standardFlowEnabled: false,
     implicitFlowEnabled: false,
     directAccessGrantsEnabled: false,
     serviceAccountsEnabled: false,
     redirectUris: [],
     webOrigins: []
   }')

GATEWAY_UUID=""
if step "client '$DOWNSTREAM_CLIENT_ID' upsert"; then
  upsert_client "$DOWNSTREAM_CLIENT_ID" "$downstream_client_payload" >/dev/null
fi
if step "client '$GATEWAY_CLIENT_ID' upsert (redirectUris=$REDIRECT_URIS)"; then
  GATEWAY_UUID=$(upsert_client "$GATEWAY_CLIENT_ID" "$gateway_client_payload")
fi

# ---------------------------------------------------------------------------
# 4) Audience Mapper — 누락 시 다운스트림이 aud 검증에 실패한다
# ---------------------------------------------------------------------------
if step "audience mapper '$AUDIENCE_MAPPER_NAME' upsert (aud += $DOWNSTREAM_CLIENT_ID)"; then
  mapper_payload=$(jq -n \
    --arg name "$AUDIENCE_MAPPER_NAME" \
    --arg audience "$DOWNSTREAM_CLIENT_ID" \
    '{
       name: $name,
       protocol: "openid-connect",
       protocolMapper: "oidc-audience-mapper",
       config: {
         "included.client.audience": $audience,
         "access.token.claim": "true",
         "id.token.claim": "false",
         "introspection.token.claim": "true"
       }
     }')

  existing_mapper_id=$(api GET "/admin/realms/$REALM/clients/$GATEWAY_UUID/protocol-mappers/models" \
    | jq -r --arg n "$AUDIENCE_MAPPER_NAME" 'map(select(.name == $n)) | .[0].id // empty')

  if [[ -n "$existing_mapper_id" ]]; then
    api PUT "/admin/realms/$REALM/clients/$GATEWAY_UUID/protocol-mappers/models/$existing_mapper_id" \
      "$(printf '%s' "$mapper_payload" | jq --arg id "$existing_mapper_id" '. + {id: $id}')" >/dev/null
    ok "audience mapper 갱신"
  else
    api POST "/admin/realms/$REALM/clients/$GATEWAY_UUID/protocol-mappers/models" "$mapper_payload" >/dev/null
    ok "audience mapper 생성"
  fi
fi

# ---------------------------------------------------------------------------
# 5) Realm roles
# ---------------------------------------------------------------------------
upsert_realm_role() {
  local role="$1" desc="$2"
  local payload
  payload=$(jq -n --arg n "$role" --arg d "$desc" '{name: $n, description: $d}')
  if [[ "$(api_status GET "/admin/realms/$REALM/roles/$role")" == "200" ]]; then
    ok "realm role '$role' 존재"
  else
    api POST "/admin/realms/$REALM/roles" "$payload" >/dev/null
    ok "realm role '$role' 생성"
  fi
}

if step "realm role '$ROLE_USER' / '$ROLE_ADMIN' upsert"; then
  upsert_realm_role "$ROLE_USER" "unigate 일반 사용자"
  upsert_realm_role "$ROLE_ADMIN" "unigate 관리자"
fi

# ---------------------------------------------------------------------------
# 6) Group + role mapping
# ---------------------------------------------------------------------------
GROUP_UUID=""
if step "group '$GROUP_USERS' upsert (+ role '$ROLE_USER')"; then
  GROUP_UUID=$(api GET "/admin/realms/$REALM/groups?search=$GROUP_USERS" \
    | jq -r --arg n "$GROUP_USERS" 'map(select(.name == $n)) | .[0].id // empty')

  if [[ -z "$GROUP_UUID" ]]; then
    api POST "/admin/realms/$REALM/groups" "$(jq -n --arg n "$GROUP_USERS" '{name: $n}')" >/dev/null
    GROUP_UUID=$(api GET "/admin/realms/$REALM/groups?search=$GROUP_USERS" \
      | jq -r --arg n "$GROUP_USERS" 'map(select(.name == $n)) | .[0].id // empty')
    ok "group '$GROUP_USERS' 생성"
  else
    ok "group '$GROUP_USERS' 존재"
  fi

  # role-mappings 는 이미 매핑돼 있어도 멱등하게 동작한다.
  role_rep=$(api GET "/admin/realms/$REALM/roles/$ROLE_USER")
  api POST "/admin/realms/$REALM/groups/$GROUP_UUID/role-mappings/realm" "[$role_rep]" >/dev/null
  ok "group '$GROUP_USERS' -> role '$ROLE_USER' 매핑"
fi

# ---------------------------------------------------------------------------
# 7) 테스트 사용자 (local 전용)
# ---------------------------------------------------------------------------
GENERATED_PASSWORD=""
if [[ "$CREATE_TEST_USERS" == "true" ]]; then
  TEST_PASSWORD="${TEST_USER_PASSWORD:-}"
  if [[ -z "$TEST_PASSWORD" ]]; then
    # head 를 파이프 선두에 둔다 — 뒤에 두면 상류가 SIGPIPE 로 죽어 pipefail 에 걸린다.
    TEST_PASSWORD="$(LC_ALL=C head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-16)"
    GENERATED_PASSWORD="$TEST_PASSWORD"
  fi

  # upsert_user <username> <email> <group-uuid | "">
  upsert_user() {
    local username="$1" email="$2" group_uuid="$3" uuid
    uuid=$(api GET "/admin/realms/$REALM/users?username=$username&exact=true" | jq -r '.[0].id // empty')

    if [[ -z "$uuid" ]]; then
      api POST "/admin/realms/$REALM/users" \
        "$(jq -n --arg u "$username" --arg e "$email" \
           '{username: $u, email: $e, emailVerified: true, enabled: true, firstName: $u}')" >/dev/null
      uuid=$(api GET "/admin/realms/$REALM/users?username=$username&exact=true" | jq -r '.[0].id')
      ok "user '$username' 생성"
    else
      ok "user '$username' 존재"
    fi

    # temporary=false — true 면 첫 로그인에서 비밀번호 변경 화면이 떠 자동화가 막힌다.
    api PUT "/admin/realms/$REALM/users/$uuid/reset-password" \
      "$(jq -n --arg p "$TEST_PASSWORD" '{type: "password", value: $p, temporary: false}')" >/dev/null

    if [[ -n "$group_uuid" ]]; then
      api PUT "/admin/realms/$REALM/users/$uuid/groups/$group_uuid" "{}" >/dev/null
      ok "user '$username' -> group '$GROUP_USERS'"
    fi
  }

  if step "테스트 사용자 alice(인가 성공) / bob(인가 실패) upsert"; then
    upsert_user "alice" "alice@example.local" "$GROUP_UUID"
    upsert_user "bob"   "bob@example.local"   ""
  fi
else
  log "alpha 환경 — 테스트 사용자는 생성하지 않습니다."
fi

# ---------------------------------------------------------------------------
# 8) 결과 출력
# ---------------------------------------------------------------------------
if [[ "$DRY_RUN" == "true" ]]; then
  echo
  warn "dry-run 종료 — 실제 변경 없음."
  exit 0
fi

CLIENT_SECRET=$(api GET "/admin/realms/$REALM/clients/$GATEWAY_UUID/client-secret" | jq -r '.value')

echo
ok "realm '$REALM' 구성 완료"
echo
echo "─────────────────────────────────────────────────────────────"
echo " 게이트웨이 주입 환경변수 ($TARGET_ENV)"
echo "─────────────────────────────────────────────────────────────"
cat <<EOF
export KEYCLOAK_ISSUER_URI="$ISSUER_URI"
export KEYCLOAK_OAUTH_CLIENT_ID="$GATEWAY_CLIENT_ID"
export KEYCLOAK_OAUTH_CLIENT_SECRET="$CLIENT_SECRET"
EOF

if [[ -n "$GENERATED_PASSWORD" ]]; then
  echo
  echo " 테스트 사용자 비밀번호 (자동 생성): $GENERATED_PASSWORD"
  echo " -> alice / bob 공통. 재실행 시 TEST_USER_PASSWORD 로 고정 가능."
fi

echo
echo "─────────────────────────────────────────────────────────────"
echo " 검증"
echo "─────────────────────────────────────────────────────────────"
cat <<EOF
curl -s $ISSUER_URI/.well-known/openid-configuration | jq '{issuer, jwks_uri}'
curl -s $ISSUER_URI/protocol/openid-connect/certs | jq '.keys[] | {kid, alg, use}'
# JWT payload 는 base64url(패딩 생략) — 패딩을 복원해 디코딩한다.
curl -s -X POST $ISSUER_URI/protocol/openid-connect/token \\
  -d grant_type=client_credentials -d client_id=$GATEWAY_CLIENT_ID \\
  --data-urlencode "client_secret=\$KEYCLOAK_OAUTH_CLIENT_SECRET" \\
  | jq -r .access_token \\
  | python3 -c 'import sys,base64,json; p=sys.stdin.read().strip().split(".")[1]; print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4)))))' \\
  | jq '{iss, aud, azp}'

# 합격 기준: 마지막 명령의 aud 배열에 "$DOWNSTREAM_CLIENT_ID" 포함
EOF
echo
warn "client secret 은 커밋하지 마세요. (.env / values-alpha.secret.yaml 은 .gitignore 대상)"
