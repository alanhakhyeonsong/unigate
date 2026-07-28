{{/*
ConfigMap — 비민감 설정 오버라이드용.

⚠️ 여기 secret 을 넣지 않는다. ConfigMap 은 평문이고 `kubectl get cm -o yaml` 로 누구나 읽는다.
민감값은 차트 밖 Secret 으로 간다(deploy-alpha.sh).
*/}}
{{- define "unigate-common.configmap" -}}
{{- $config := default (dict) .Values.config -}}
{{- if and $config.enabled (or $config.files $config.properties) -}}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "unigate-common.fullname" . }}-cm
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
data:
  {{- range $name, $content := default (dict) $config.files }}
  {{ $name | quote }}: |
{{- if kindIs "string" $content }}
{{ $content | indent 4 }}
{{- else }}
{{ toYaml $content | indent 4 }}
{{- end }}
  {{- end }}
  {{- if and $config.properties (not (hasKey (default (dict) $config.files) "application.properties")) }}
  application.properties: |
{{ $config.properties | indent 4 }}
  {{- end }}
{{- end -}}
{{- end -}}

{{/*
tmp 용 PVC.

기본은 **만들지 않는다**(emptyDir). PVC 가 필요한 경우는 파드 재시작 사이에 /tmp 내용이
남아야 할 때뿐인데, 이 앱들의 /tmp 는 `-Djava.io.tmpdir` 과 nginx 임시 파일 용도라
남을 필요가 없다. 반면 PVC(RWO)는 스케일아웃을 막는 실질적 제약이 된다.
*/}}
{{- define "unigate-common.pvc" -}}
{{- $tmp := default (dict) .Values.tmpVolume -}}
{{- $pvc := default (dict) $tmp.persistentVolumeClaim -}}
{{- if and $tmp.enabled $pvc.enabled -}}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {{ printf "%s-pvc" (default (printf "%s-tmp" (include "unigate-common.fullname" .)) $pvc.name) }}
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
spec:
  accessModes:
    {{- range default (list "ReadWriteOnce") $pvc.accessModes }}
    - {{ . }}
    {{- end }}
  {{- with $pvc.storageClassName }}
  storageClassName: {{ . }}
  {{- end }}
  resources:
    {{- toYaml (default (dict "requests" (dict "storage" "1Gi")) $pvc.resources) | nindent 4 }}
{{- end -}}
{{- end -}}
