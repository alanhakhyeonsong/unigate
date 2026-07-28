{{- define "unigate-common.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "unigate-common.fullname" . }}-svc
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "unigate-common.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.targetPort }}
      protocol: TCP
{{- end -}}

{{- define "unigate-common.serviceaccount" -}}
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "unigate-common.serviceAccountName" . }}
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- with .Values.serviceAccount.automount }}
automountServiceAccountToken: {{ . }}
{{- end }}
{{- end -}}
{{- end -}}
