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

# IAM 서비스 전용 관리 client (Phase 8c).
# 게이트웨이 로그인 client 와 **분리**한다 — 관리 자격증명이 유출돼도 로그인 흐름은 무사하고
# 그 반대도 마찬가지다(blast radius 축소, IAM_PLATFORM_DECISION.md §14).
readonly IAM_CLIENT_ID="unigate-iam"
# IAM 을 **audience 로도** 쓴다 (Phase 8f). GW 가 relay 한 사용자 토큰의 `aud` 에 이 값이 없으면
# IAM Resource Server 가 401 로 거부한다. audience 전용 client 를 따로 만들지 않는 이유는
# 수신자가 곧 IAM 이고 그 client 가 이미 있기 때문이다.
readonly IAM_AUDIENCE_MAPPER_NAME="iam-audience"
readonly REALM_MANAGEMENT_CLIENT_ID="realm-management"
# 최소권한: realm-admin 전체가 아니라 사용자 관리에 필요한 것만.
# realm-admin 을 주면 client·realm 설정까지 바꿀 수 있어 과잉이다.
readonly IAM_SERVICE_ACCOUNT_ROLES=("manage-users" "view-users" "query-users")

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

# IAM 서비스 관리 client — 로그인 흐름에 참여하지 않는다(standardFlow=false).
# service account 로만 Keycloak Admin API 를 호출한다.
iam_client_payload=$(jq -n \
  --arg clientId "$IAM_CLIENT_ID" \
  '{
     clientId: $clientId,
     name: "unigate IAM Service (admin)",
     description: "IAM 서비스의 Keycloak Admin API 접근용. service account 전용이며 로그인에 쓰지 않는다.",
     enabled: true,
     protocol: "openid-connect",
     publicClient: false,
     standardFlowEnabled: false,
     implicitFlowEnabled: false,
     directAccessGrantsEnabled: false,
     serviceAccountsEnabled: true,
     authorizationServicesEnabled: false,
     # ⚠️ true 여야 한다. false 로 두면 service account 에 realm-management 역할을 부여해도
     # **토큰에 실리지 않아** Admin API 가 전부 403 이 된다(실제로 겪었다 — 토큰은 발급되고
     # resource_access 가 null 이라 원인을 찾기 어렵다). §4.6 이 경고한 함정과 같은 구조다.
     #
     # 최소권한은 유지된다: 이 client 는 사용자 로그인을 하지 않으므로(standardFlow=false)
     # 토큰에 실리는 것은 **service account 에 부여된 역할뿐**이고, 그건 아래 세 개로 한정된다.
     fullScopeAllowed: true,
     redirectUris: [],
     webOrigins: []
   }')

GATEWAY_UUID=""
IAM_UUID=""
if step "client '$DOWNSTREAM_CLIENT_ID' upsert"; then
  upsert_client "$DOWNSTREAM_CLIENT_ID" "$downstream_client_payload" >/dev/null
fi
if step "client '$GATEWAY_CLIENT_ID' upsert (redirectUris=$REDIRECT_URIS)"; then
  GATEWAY_UUID=$(upsert_client "$GATEWAY_CLIENT_ID" "$gateway_client_payload")
fi
if step "client '$IAM_CLIENT_ID' upsert (service account 전용)"; then
  IAM_UUID=$(upsert_client "$IAM_CLIENT_ID" "$iam_client_payload")
fi

# ---------------------------------------------------------------------------
# 3-1) IAM service account 에 realm-management 역할 부여 (최소권한)
# ---------------------------------------------------------------------------
# service account 는 client 마다 자동 생성되는 **사용자**다. 그 사용자에게 realm-management client 의
# 역할을 붙여야 Admin API 를 호출할 수 있다. 역할을 안 붙이면 토큰은 발급되지만 API 가 403 을 준다
# — 증상이 "인증은 되는데 권한이 없다" 라 원인을 찾기 어렵다.
# ⚠️ `step` 을 **먼저** 평가해야 한다. dry-run 에서는 client 를 실제로 만들지 않아 `IAM_UUID` 가 비는데,
# `[[ -n "$IAM_UUID" ]]` 를 앞에 두면 이 단계가 dry-run 출력에서 통째로 사라진다.
# 그러면 "dry-run 에서 봤으니 괜찮겠지" 가 성립하지 않는다 — dry-run 의 목적이 무너진다.
if step "IAM service account 역할 부여 (${IAM_SERVICE_ACCOUNT_ROLES[*]})"; then
  [[ -n "$IAM_UUID" ]] || die "IAM client UUID 를 얻지 못했습니다 (client upsert 실패)"
  sa_user_id=$(api GET "/admin/realms/$REALM/clients/$IAM_UUID/service-account-user" | jq -r '.id // empty')
  [[ -n "$sa_user_id" ]] || die "service account 사용자를 찾지 못했습니다 (serviceAccountsEnabled 확인)"

  rm_uuid=$(api GET "/admin/realms/$REALM/clients?clientId=$REALM_MANAGEMENT_CLIENT_ID" \
    | jq -r '.[0].id // empty')
  [[ -n "$rm_uuid" ]] || die "'$REALM_MANAGEMENT_CLIENT_ID' client 를 찾지 못했습니다"

  role_payload="[]"
  for role_name in "${IAM_SERVICE_ACCOUNT_ROLES[@]}"; do
    role_json=$(api GET "/admin/realms/$REALM/clients/$rm_uuid/roles/$role_name")
    role_payload=$(jq -n --argjson acc "$role_payload" --argjson r "$role_json" \
      '$acc + [{id: $r.id, name: $r.name}]')
  done

  # 이미 부여된 역할을 다시 POST 해도 Keycloak 은 중복을 만들지 않는다(멱등).
  api POST "/admin/realms/$REALM/users/$sa_user_id/role-mappings/clients/$rm_uuid" "$role_payload" >/dev/null
  ok "IAM service account 역할 부여 완료 (${IAM_SERVICE_ACCOUNT_ROLES[*]})"
fi

# ---------------------------------------------------------------------------
# 4) Audience Mapper — 누락 시 수신 서비스가 aud 검증에 실패한다
# ---------------------------------------------------------------------------
# 매퍼는 **게이트웨이 로그인 client 에** 붙는다. 토큰을 발급받는 주체가 그쪽이기 때문이다
# (수신자 client 에 붙이면 아무 효과가 없다 — 그 client 는 토큰을 발급받지 않는다).
#
# 한 토큰에 audience 가 여럿인 것은 정상이다. 각 수신 서비스는 "내 이름이 aud 에 있는가"만 본다.
upsert_audience_mapper() {
  local mapper_name="$1" audience_client="$2"
  local mapper_payload existing_mapper_id

  mapper_payload=$(jq -n \
    --arg name "$mapper_name" \
    --arg audience "$audience_client" \
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
    | jq -r --arg n "$mapper_name" 'map(select(.name == $n)) | .[0].id // empty')

  if [[ -n "$existing_mapper_id" ]]; then
    api PUT "/admin/realms/$REALM/clients/$GATEWAY_UUID/protocol-mappers/models/$existing_mapper_id" \
      "$(printf '%s' "$mapper_payload" | jq --arg id "$existing_mapper_id" '. + {id: $id}')" >/dev/null
    ok "audience mapper '$mapper_name' 갱신 (aud += $audience_client)"
  else
    api POST "/admin/realms/$REALM/clients/$GATEWAY_UUID/protocol-mappers/models" "$mapper_payload" >/dev/null
    ok "audience mapper '$mapper_name' 생성 (aud += $audience_client)"
  fi
}

if step "audience mapper '$AUDIENCE_MAPPER_NAME' upsert (aud += $DOWNSTREAM_CLIENT_ID)"; then
  upsert_audience_mapper "$AUDIENCE_MAPPER_NAME" "$DOWNSTREAM_CLIENT_ID"
fi

# Phase 8f — IAM Resource Server 용. 이게 없으면 GW→IAM 인증 라우트가 **전부 401** 이고,
# 응답만 봐서는 원인이 보이지 않는다(토큰을 디코드해 aud 를 눈으로 봐야 안다).
if step "audience mapper '$IAM_AUDIENCE_MAPPER_NAME' upsert (aud += $IAM_CLIENT_ID)"; then
  upsert_audience_mapper "$IAM_AUDIENCE_MAPPER_NAME" "$IAM_CLIENT_ID"
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
  #
  # firstName/lastName 을 **둘 다** 채운다. Keycloak 의 declarative user profile 은 기본적으로
  # 두 항목을 required 로 두기 때문에, 하나라도 비면 로그인 마지막 단계에서
  # VERIFY_PROFILE("Update Account Information") 화면이 끼어들어 Authorization Code Flow 가
  # 콜백까지 도달하지 못한다. 자동화 검증이 조용히 막히는 지점이다.
  upsert_user() {
    local username="$1" email="$2" group_uuid="$3" uuid
    local profile
    profile="$(jq -n --arg u "$username" --arg e "$email" \
      '{username: $u, email: $e, emailVerified: true, enabled: true, firstName: $u, lastName: "tester"}')"
    uuid=$(api GET "/admin/realms/$REALM/users?username=$username&exact=true" | jq -r '.[0].id // empty')

    if [[ -z "$uuid" ]]; then
      api POST "/admin/realms/$REALM/users" "$profile" >/dev/null
      uuid=$(api GET "/admin/realms/$REALM/users?username=$username&exact=true" | jq -r '.[0].id')
      ok "user '$username' 생성"
    else
      # 이미 있는 사용자도 프로필을 맞춘다. 예전 버전이 만든 계정은 lastName 이 비어 있어
      # 재실행만으로는 고쳐지지 않는다("존재"로 건너뛰므로).
      api PUT "/admin/realms/$REALM/users/$uuid" "$profile" >/dev/null
      ok "user '$username' 존재 — 프로필 갱신"
    fi

    # temporary=false — true 면 첫 로그인에서 비밀번호 변경 화면이 떠 자동화가 막힌다.
    api PUT "/admin/realms/$REALM/users/$uuid/reset-password" \
      "$(jq -n --arg p "$TEST_PASSWORD" '{type: "password", value: $p, temporary: false}')" >/dev/null

    if [[ -n "$group_uuid" ]]; then
      api PUT "/admin/realms/$REALM/users/$uuid/groups/$group_uuid" "{}" >/dev/null
      ok "user '$username' -> group '$GROUP_USERS'"
    fi
  }

  # assign_realm_role <username> <role>
  #
  # 그룹이 아니라 **realm role 을 직접** 매핑한다. Phase 9c 의 관리 API 는 토큰의
  # `realm_access.roles` 에 실린 역할로 인가하므로, 역할이 실제로 사용자에게 붙어 있어야 한다.
  #
  # ⚠️ 역할이 realm 에 정의만 되어 있고 아무에게도 할당되지 않으면 토큰에 실리지 않는다.
  # 그러면 관리 API 는 **영원히 403** 이고, realm 관리 콘솔에서 역할이 보이므로
  # "역할은 있는데 왜 안 되지" 로 헤매기 쉽다.
  assign_realm_role() {
    local username="$1" role="$2" uuid role_rep
    uuid=$(api GET "/admin/realms/$REALM/users?username=$username&exact=true" | jq -r '.[0].id // empty')
    if [[ -z "$uuid" ]]; then
      warn "user '$username' 을 찾지 못해 role '$role' 매핑을 건너뜁니다."
      return
    fi
    role_rep=$(api GET "/admin/realms/$REALM/roles/$role")
    # role-mappings 는 이미 매핑돼 있어도 멱등하다.
    api POST "/admin/realms/$REALM/users/$uuid/role-mappings/realm" "[$role_rep]" >/dev/null
    ok "user '$username' -> realm role '$role'"
  }

  if step "테스트 사용자 alice(인가 성공) / bob(인가 실패) / carol(관리자) upsert"; then
    upsert_user "alice" "alice@example.local" "$GROUP_UUID"
    upsert_user "bob"   "bob@example.local"   ""
    # carol 은 Phase 9c 관리 API 검증용이다. alice 에게 admin 을 얹지 않는 이유:
    # alice 는 "일반 사용자" 시나리오의 기준점이라, 관리 권한을 주면 그 시나리오가 오염된다.
    upsert_user "carol" "carol@example.local" "$GROUP_UUID"
    assign_realm_role "carol" "$ROLE_ADMIN"
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
IAM_CLIENT_SECRET=$(api GET "/admin/realms/$REALM/clients/$IAM_UUID/client-secret" | jq -r '.value')

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

echo
echo "─────────────────────────────────────────────────────────────"
echo " IAM 서비스 주입 환경변수 ($TARGET_ENV) — Phase 8c"
echo "─────────────────────────────────────────────────────────────"
cat <<EOF
export KEYCLOAK_URL="$KEYCLOAK_URL"
export KEYCLOAK_REALM="$REALM"
export KEYCLOAK_IAM_CLIENT_ID="$IAM_CLIENT_ID"
export KEYCLOAK_IAM_CLIENT_SECRET="$IAM_CLIENT_SECRET"
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

# 합격 기준: 마지막 명령의 aud 배열에 "$DOWNSTREAM_CLIENT_ID" 와 "$IAM_CLIENT_ID" 가 **둘 다** 포함
#   - "$DOWNSTREAM_CLIENT_ID" 누락 -> 다운스트림이 401 (Phase 1)
#   - "$IAM_CLIENT_ID" 누락        -> GW→IAM 인증 라우트가 전부 401 (Phase 8f)

# ── IAM service account 검증 (Phase 8c) ──────────────────────────────
# 토큰이 발급돼도 역할이 없으면 Admin API 가 403 을 준다. 토큰과 권한을 **따로** 확인한다.
IAM_TOKEN=\$(curl -s -X POST $ISSUER_URI/protocol/openid-connect/token \\
  -d grant_type=client_credentials -d client_id=$IAM_CLIENT_ID \\
  --data-urlencode "client_secret=\$KEYCLOAK_IAM_CLIENT_SECRET" | jq -r .access_token)

# 합격 기준: HTTP 200 (403 이면 realm-management 역할이 안 붙은 것)
curl -s -o /dev/null -w '%{http_code}\\n' \\
  -H "Authorization: Bearer \$IAM_TOKEN" \\
  "$KEYCLOAK_URL/admin/realms/$REALM/users?max=1"
EOF
echo
warn "client secret 은 커밋하지 마세요. (.env / values-alpha.secret.yaml 은 .gitignore 대상)"
