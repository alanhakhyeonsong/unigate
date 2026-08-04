#!/usr/bin/env bash
#
# unigate alpha 배포 — 로컬에서 직접 (CI/CD 없음).
#
#   빌드 → 이미지 → 푸시 → Secret(kubectl) → Helm upgrade
#
# ── 이 스크립트가 지키는 원칙 ─────────────────────────────────────────────────
#
# 1. **비밀은 Helm values 를 거치지 않는다.**
#    Helm 은 릴리즈 values 를 클러스터의 `sh.helm.release.v1.*` Secret 에 통째로 보관해서,
#    values 에 비밀을 넣으면 `helm get values -a` 로 평문이 그대로 나온다. git 을 막아도
#    그 경로는 남는다. 그래서 Secret 은 kubectl 로 따로 만들고 차트는 이름으로 참조만 한다.
#
# 2. **실제 좌표는 커밋 대상 values 에 없다.**
#    네임스페이스·호스트·레지스트리는 gitignore 된 좌표 파일에서 읽어 **임시 values 파일**로
#    넘긴다. `--set ingress.hosts[0].host=...` 를 쓰지 않는 이유는 배열 인덱스 표기가
#    오타 시 조용히 다른 항목을 만들고, zsh 에서는 `[0]` 이 glob 으로 먹혀 실행조차 안 되기 때문이다.
#
# 3. **Secret 이 바뀌면 파드가 다시 뜬다.**
#    envFrom 으로 읽는 Secret 은 내용이 바뀌어도 k8s 가 파드를 재시작하지 않는다.
#    해시를 podAnnotation 에 넣어 Deployment 를 변경시킨다.
#
# 사용법:
#   deploy/deploy-alpha.sh [옵션] <app>
#     app: gateway | iam | demo-be | demo-billing | demo-fe | all
#
#   옵션:
#     --dry-run       helm/kubectl 을 dry-run 으로만 실행(이미지 빌드·푸시는 건너뜀)
#     --skip-build    이미 만든 이미지를 그대로 쓴다(--tag 와 함께)
#     --tag <tag>     이미지 태그 지정(기본: <timestamp>-<git sha>)
#     --env <name>    환경 이름(기본: alpha). deploy/env/<name>.*.env 를 읽는다
#     --overlay <f>   values 를 하나 더 얹는다(부하테스트 프로파일 등). 여러 번 지정 가능
#     --yes           배포 대상 확인 프롬프트를 건너뛴다(비대화 실행용)
#
# 사전 조건:
#   - docker login <registry> / kubectl 컨텍스트가 대상 클러스터를 가리킬 것
#   - deploy/env/alpha.coord.env 와 앱별 alpha.<app>.secret.env 작성(.example 참조)
#   - 공유 PostgreSQL 에 `unigate` · `unigate_iam` DB 사전 생성
#   - Keycloak realm 준비: scripts/keycloak/setup-realm.sh --env alpha
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_NAME="alpha"
DRY_RUN=false
SKIP_BUILD=false
TAG=""
APP=""
ASSUME_YES=false
OVERLAYS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)    DRY_RUN=true; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --tag)        TAG="${2:?--tag 값이 필요합니다}"; shift 2 ;;
    --env)        ENV_NAME="${2:?--env 값이 필요합니다}"; shift 2 ;;
    --overlay)    OVERLAYS+=("${2:?--overlay 값이 필요합니다}"); shift 2 ;;
    --yes|-y)     ASSUME_YES=true; shift ;;
    -h|--help)    sed -n '2,35p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*)           echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
    *)            APP="$1"; shift ;;
  esac
done

[[ -n "${APP}" ]] || { echo "ERROR: 배포 대상을 지정하세요 — gateway | iam | demo-be | demo-billing | demo-fe | all" >&2; exit 2; }

ENV_DIR="deploy/env"
HELM_DIR="deploy/helm"
COORD_FILE="${ENV_DIR}/${ENV_NAME}.coord.env"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR\033[0m %s\n' "$*" >&2; exit 1; }

# macOS 에는 sha256sum 이 없다.
file_hash() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -c1-16
  else shasum -a 256 "$1" | cut -c1-16; fi
}

[[ -f "${COORD_FILE}" ]] || die "${COORD_FILE} 가 없습니다. ${COORD_FILE}.example 를 복사해 채우세요."
# shellcheck disable=SC1090
set -a; source "${COORD_FILE}"; set +a

: "${UNIGATE_NAMESPACE:?좌표 파일에 UNIGATE_NAMESPACE 가 필요합니다}"
: "${UNIGATE_REGISTRY:?좌표 파일에 UNIGATE_REGISTRY 가 필요합니다}"
: "${UNIGATE_TLS_SECRET:=ssl-common}"
: "${UNIGATE_IMAGE_PULL_SECRET:=harbor-pull}"

# ⚠️ 빌드 대상 플랫폼을 **반드시 명시한다.**
#
# Apple Silicon(arm64) Mac 에서 그냥 `docker build` 하면 arm64 이미지가 만들어지는데,
# 클러스터 노드는 amd64 다. 그러면 push 는 멀쩡히 성공하고 파드에서만
#   `no match for platform in manifest: not found`
# 로 ImagePullBackOff 가 난다 — **레지스트리에는 이미지가 보이므로** push 문제로 오해하기 쉽다.
# 실제로 한 번 겪었다. 다른 아키텍처의 노드를 쓴다면 이 값을 바꾼다.
: "${UNIGATE_PLATFORM:=linux/amd64}"

if [[ -z "${TAG}" ]]; then
  GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
  TAG="$(date +%Y%m%d%H%M%S)-${GIT_SHA}"
fi

KUBE_CONTEXT="$(kubectl config current-context 2>/dev/null || echo '<없음>')"
log "환경=${ENV_NAME} / 네임스페이스=${UNIGATE_NAMESPACE} / 컨텍스트=${KUBE_CONTEXT} / 태그=${TAG}"
if [[ "${DRY_RUN}" == "false" && "${ASSUME_YES}" == "false" ]]; then
  # 클러스터에 쓰는 동작이라 대상을 눈으로 확인시킨다. 컨텍스트가 다른 클러스터를
  # 가리킨 채 배포하는 사고는 조용히 성공하기 때문에 더 위험하다.
  #
  # ⚠️ 비대화 실행(CI·백그라운드)에서는 stdin 이 없어 read 가 즉시 EOF 를 받는다.
  #    그 경우 "중단"으로 처리되므로, 의도적 자동 실행에는 --yes 를 명시한다.
  #    기본값을 자동 승인으로 두지 않는 이유는 이 확인이 유일한 오배포 방지선이기 때문이다.
  read -r -p "위 대상에 배포합니다. 계속할까요? [y/N] " ans
  [[ "${ans}" == "y" || "${ans}" == "Y" ]] || die "사용자가 중단했습니다."
elif [[ "${ASSUME_YES}" == "true" && "${DRY_RUN}" == "false" ]]; then
  log "--yes 지정됨 — 확인 프롬프트를 건너뜁니다."
fi

# app | chart | gradle 모듈 | dockerfile | 빌드 컨텍스트 | Secret 필요 | ingress host 변수
app_meta() {
  case "$1" in
    gateway) echo "unigate-gateway|gateway|docker/server.dockerfile|.|true|UNIGATE_GATEWAY_HOST" ;;
    iam)     echo "unigate-iam|iam|docker/server.dockerfile|.|true|" ;;
    demo-be) echo "unigate-demo-be|samples/downstream-demo|docker/server.dockerfile|.|true|" ;;
    demo-billing) echo "unigate-demo-billing|samples/downstream-billing|docker/server.dockerfile|.|true|" ;;
    demo-fe) echo "unigate-demo-fe|-|samples/frontend-demo/Dockerfile|samples/frontend-demo|false|UNIGATE_CONSOLE_HOST" ;;
    *)       die "알 수 없는 앱: $1 (gateway | iam | demo-be | demo-billing | demo-fe | all)" ;;
  esac
}

build_image() {
  local app="$1" module="$2" dockerfile="$3" context="$4" image="$5"

  case "${module}" in
    "-")
      log "[${app}] jar 빌드 없음 — FE 는 이미지 안에서(multi-stage) 빌드된다" ;;
    samples/*)
      # 샘플은 settings.gradle.kts 에 include 되지 않은 **독립 빌드**다.
      # 루트 ./gradlew 로는 이 모듈을 알 수 없다.
      #
      # 샘플이 둘이 되면서 경로 리터럴 분기를 glob 으로 바꿨다. 하드코딩을 유지하면
      # 샘플을 추가할 때마다 이 case 를 잊고, 그러면 **직전 빌드의 jar 이 그대로 이미지에
      # 들어간다** — 빌드도 배포도 성공하고 코드만 옛것이라 증상이 가장 늦게 드러난다.
      log "[${app}] bootJar (독립 Gradle 빌드: ${module})"
      (cd "${module}" && ./gradlew bootJar --quiet) ;;
    *)
      log "[${app}] bootJar :${module}"
      ./gradlew ":${module}:bootJar" --quiet ;;
  esac

  local build_args=(--platform "${UNIGATE_PLATFORM}")
  [[ "${module}" != "-" ]] && build_args+=(--build-arg "MODULE_NAME=${module}")

  log "[${app}] docker build ${image} (platform=${UNIGATE_PLATFORM})"
  docker build -f "${dockerfile}" "${build_args[@]}" -t "${image}" "${context}"

  # push 전에 실제로 그 플랫폼으로 만들어졌는지 확인한다. 여기서 걸러내지 않으면
  # 다음 단계가 전부 성공한 뒤 **파드에서만** ImagePullBackOff 로 드러난다.
  local built
  built="$(docker image inspect "${image}" --format '{{.Os}}/{{.Architecture}}')"
  [[ "${built}" == "${UNIGATE_PLATFORM}" ]] \
    || die "[${app}] 빌드된 이미지가 ${built} 입니다(기대: ${UNIGATE_PLATFORM}). 노드 아키텍처와 맞지 않으면 파드가 뜨지 않습니다."

  log "[${app}] docker push ${image}"
  docker push "${image}"
}

apply_secret() {
  local app="$1" secret_name="$2" secret_file="$3"

  [[ -f "${secret_file}" ]] || die "[${app}] ${secret_file} 가 없습니다. ${secret_file}.example 를 복사해 채우세요."

  # 빈 값이 남아 있으면 막는다. 빈 문자열이 주입되면 앱은 뜨지만 placeholder 미해결 예외가
  # 나지 않고 **잘못된 좌표로 조용히 동작**한다 — fail-closed 설계가 무력해지는 자리다.
  local empty
  empty="$(grep -vE '^[[:space:]]*(#|$)' "${secret_file}" | grep -E '=[[:space:]]*$' | cut -d= -f1 | tr '\n' ' ' || true)"
  [[ -z "${empty}" ]] || die "[${app}] ${secret_file} 에 빈 값이 있습니다: ${empty}"

  log "[${app}] Secret 적용 ${secret_name}"
  if [[ "${DRY_RUN}" == "true" ]]; then
    kubectl create secret generic "${secret_name}" \
      --from-env-file="${secret_file}" --namespace "${UNIGATE_NAMESPACE}" \
      --dry-run=client -o yaml > /dev/null
    echo "    (dry-run) ${secret_name} 생성 검증 통과"
  else
    kubectl create secret generic "${secret_name}" \
      --from-env-file="${secret_file}" --namespace "${UNIGATE_NAMESPACE}" \
      --dry-run=client -o yaml | kubectl apply -f -
  fi
}

deploy_app() {
  local app="$1"
  IFS='|' read -r chart module dockerfile context needs_secret host_var <<< "$(app_meta "${app}")"

  local image="${UNIGATE_REGISTRY}/${chart}:${TAG}"
  local secret_name="${chart}-secret"
  local secret_file="${ENV_DIR}/${ENV_NAME}.${app}.secret.env"

  if [[ "${SKIP_BUILD}" == "false" && "${DRY_RUN}" == "false" ]]; then
    build_image "${app}" "${module}" "${dockerfile}" "${context}" "${image}"
  else
    log "[${app}] 빌드/푸시 건너뜀 (image=${image})"
  fi

  local secret_hash="none"
  if [[ "${needs_secret}" == "true" ]]; then
    apply_secret "${app}" "${secret_name}" "${secret_file}"
    secret_hash="$(file_hash "${secret_file}")"
  fi

  local tmp_values
  tmp_values="$(mktemp -t unigate-values)"
  # 실제 좌표가 담긴 파일이다. 종료 경로가 여럿(set -e 포함)이라 trap 으로 지운다.
  trap 'rm -f "${tmp_values}"' RETURN

  {
    echo "global:"
    echo "  namespace: ${UNIGATE_NAMESPACE}"
    echo "  image:"
    echo "    repository: ${UNIGATE_REGISTRY}/${chart}"
    echo "    tag: \"${TAG}\""
    echo "imagePullSecrets:"
    echo "  - name: ${UNIGATE_IMAGE_PULL_SECRET}"
    echo "podAnnotations:"
    # Secret 내용이 바뀌면 이 값이 바뀌고 → Deployment 가 바뀌고 → 롤링이 돈다.
    echo "  unigate.io/secret-hash: \"${secret_hash}\""

    if [[ -n "${host_var}" ]]; then
      local host="${!host_var:-}"
      [[ -n "${host}" ]] || die "[${app}] 좌표 파일에 ${host_var} 가 필요합니다."
      echo "ingress:"
      echo "  enabled: true"
      echo "  hosts:"
      echo "    - host: ${host}"
      echo "      paths:"
      echo "        - path: /"
      echo "          pathType: Prefix"
      echo "  tls:"
      echo "    - secretName: ${UNIGATE_TLS_SECRET}"
      echo "      hosts:"
      echo "        - ${host}"
    fi

    # FE 는 게이트웨이 주소를 **런타임에** 주입받는다(이미지에 박지 않는다).
    if [[ "${app}" == "demo-fe" ]]; then
      : "${UNIGATE_GATEWAY_HOST:?demo-fe 배포에는 UNIGATE_GATEWAY_HOST 가 필요합니다}"
      echo "env:"
      echo "  API_BASE_URL: https://${UNIGATE_GATEWAY_HOST}"
    fi
  } > "${tmp_values}"

  log "[${app}] helm dependency update"
  helm dependency update "${HELM_DIR}/${chart}" >/dev/null

  # -f 는 **뒤에 온 것이 이긴다.** 좌표(tmp_values)를 맨 뒤에 두어 오버레이가 실수로
  # 네임스페이스나 이미지 태그를 덮어쓰지 못하게 한다.
  local helm_args=(
    upgrade --install "${chart}" "${HELM_DIR}/${chart}"
    -f "${HELM_DIR}/${chart}/values-${ENV_NAME}.yaml"
  )
  local ov
  for ov in "${OVERLAYS[@]:-}"; do
    [[ -n "${ov}" ]] || continue
    [[ -f "${ov}" ]] || die "[${app}] 오버레이 파일이 없습니다: ${ov}"
    log "[${app}] 오버레이 적용: ${ov}"
    helm_args+=(-f "${ov}")
  done
  helm_args+=(
    -f "${tmp_values}"
    --namespace "${UNIGATE_NAMESPACE}"
    --create-namespace
  )

  if [[ "${DRY_RUN}" == "true" ]]; then
    log "[${app}] helm upgrade --dry-run=client"
    # =client 를 명시한다. 인자 없는 --dry-run 은 deprecated 이고, server 모드는
    # 클러스터에 접근해 admission 까지 태우므로 좌표만 검증하려는 목적과 다르다.
    helm "${helm_args[@]}" --dry-run=client > /dev/null
    echo "    (dry-run) ${chart} 렌더 통과"
  else
    log "[${app}] helm upgrade --install (--wait)"
    # 롤아웃 완료까지 기다린다. 실패하면 다음 앱으로 넘어가지 않는다 —
    # 순서 의존이 있는 배포에서 앞이 깨진 채 뒤를 올리면 원인이 섞인다.
    helm "${helm_args[@]}" --wait --timeout 5m
  fi
}

# 배포 순서: IAM · demo-be · demo-billing 을 먼저 올린다. GW 가 세 Service 를 URI 로 참조한다.
# 순서를 뒤집어도 GW 는 뜨지만 해당 라우트가 CB open 으로 503 을 낸다.
# demo-fe 는 게이트웨이 주소를 주입받으므로 마지막이다.
if [[ "${APP}" == "all" ]]; then
  for a in iam demo-be demo-billing gateway demo-fe; do deploy_app "$a"; done
else
  deploy_app "${APP}"
fi

log "완료 (tag=${TAG})"
if [[ "${DRY_RUN}" == "false" ]]; then
  cat <<EOF

확인:
  kubectl -n ${UNIGATE_NAMESPACE} get pods,hpa,ingress
  kubectl -n ${UNIGATE_NAMESPACE} logs deploy/unigate-gateway-deploy --tail=50
EOF
fi
