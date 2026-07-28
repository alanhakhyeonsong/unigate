{{/*
이름·레이블 헬퍼.

기본값은 **차트 이름**이다(`unigate-gateway` 등). `global.name` 으로 덮을 수 있게 남겨 두지만,
모듈별 차트 구조에서는 차트 이름이 곧 앱 이름이라 대개 설정할 일이 없다.
*/}}

{{- define "unigate-common.name" -}}
{{- default .Chart.Name (dig "name" "" (default (dict) .Values.global)) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "unigate-common.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "unigate-common.name" . -}}
{{- end -}}
{{- end -}}

{{- define "unigate-common.namespace" -}}
{{- default .Release.Namespace (dig "namespace" "" (default (dict) .Values.global)) -}}
{{- end -}}

{{- define "unigate-common.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "unigate-common.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: unigate
{{- end -}}

{{/*
⚠️ selectorLabels 에는 **변할 수 있는 값을 넣지 않는다.**
Deployment 의 `spec.selector` 는 생성 후 immutable 이라, 여기 버전이나 이미지 태그가 섞이면
다음 배포에서 `field is immutable` 로 업그레이드가 통째로 실패한다.
*/}}
{{- define "unigate-common.selectorLabels" -}}
app.kubernetes.io/name: {{ include "unigate-common.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "unigate-common.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- printf "%s-sa" (default (include "unigate-common.fullname" .) .Values.serviceAccount.name) -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
이미지 좌표. global 이 있으면 우선한다 — 레지스트리는 환경 단위로 바뀌고 앱 단위로는 잘 안 바뀐다.
*/}}
{{- define "unigate-common.image" -}}
{{- $global := default (dict) (dig "image" (dict) (default (dict) .Values.global)) -}}
{{- $local := default (dict) .Values.image -}}
{{- $repo := default (dig "repository" "" $local) (dig "repository" "" $global) -}}
{{- $tag := default (dig "tag" (.Chart.AppVersion | toString) $local) (dig "tag" "" $global) -}}
{{- if not $repo -}}
{{- fail "image.repository (또는 global.image.repository) 가 비어 있습니다 — 레지스트리 경로를 주입하세요." -}}
{{- end -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}

{{- define "unigate-common.imagePullPolicy" -}}
{{- $global := default (dict) (dig "image" (dict) (default (dict) .Values.global)) -}}
{{- $local := default (dict) .Values.image -}}
{{- default (dig "pullPolicy" "IfNotPresent" $local) (dig "pullPolicy" "" $global) -}}
{{- end -}}

{{/*
이 앱이 참조하는 **차트 밖 Secret** 이름.

secret 은 Helm 이 만들지 않는다(`deploy/deploy-alpha.sh` 가 kubectl 로 생성).
자세한 근거는 앱 차트 values 의 `existingSecret` 주석 참조.
*/}}
{{- define "unigate-common.secretName" -}}
{{- default (printf "%s-secret" (include "unigate-common.fullname" .)) .Values.existingSecret -}}
{{- end -}}
