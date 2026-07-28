{{/*
Ingress.

⚠️ host 는 실제 좌표라 커밋 대상 values 에 실값을 두지 않는다(CLAUDE.md §8).
배포 스크립트가 gitignore 된 좌표 파일에서 읽어 `--set` 으로 주입한다.
그래서 host 가 비어 있으면 렌더 자체를 실패시킨다 — 빈 host 로 Ingress 가 생기면
"모든 호스트" 규칙이 되어 다른 서비스의 트래픽까지 삼킬 수 있다.
*/}}
{{- define "unigate-common.ingress" -}}
{{- if .Values.ingress.enabled -}}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "unigate-common.fullname" . }}-ing
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- with .Values.ingress.className }}
  ingressClassName: {{ . }}
  {{- end }}
  rules:
    {{- range .Values.ingress.hosts }}
    {{- if not .host }}
    {{- fail "ingress.hosts[].host 가 비어 있습니다 — 배포 스크립트의 --set 주입을 확인하세요." }}
    {{- end }}
    - host: {{ .host | quote }}
      http:
        paths:
          {{- range .paths }}
          - path: {{ .path }}
            pathType: {{ .pathType }}
            backend:
              service:
                name: {{ include "unigate-common.fullname" $ }}-svc
                port:
                  number: {{ $.Values.service.port }}
          {{- end }}
    {{- end }}
  {{- with .Values.ingress.tls }}
  tls:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end -}}
{{- end -}}
