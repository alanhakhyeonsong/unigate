{{/*
HorizontalPodAutoscaler.

## 스케일 안전성은 애플리케이션이 이미 갖췄다
- gateway: 세션이 Valkey 에 있어 파드가 늘어도 로그인이 유지된다(sticky 불필요)
- iam: outbox 워커가 `FOR UPDATE SKIP LOCKED` 로 클레임해 중복 처리가 없다

## 그래서 남는 함정은 두 가지다
1. `tmpVolume` 이 PVC(RWO)면 두 번째 파드가 다른 노드에서 Pending 에 걸린다 → emptyDir 필수
2. behavior 를 비워 두면 부하 시험 중 **스케일 플랩**이 난다.
   k6 램프업/다운은 부하가 계단식으로 변해, 기본 안정화 창(scaleDown 300s)만으로도
   파드 수가 오르내리며 측정값이 흔들린다. 올릴 때는 빠르게, 내릴 때는 느리게 둔다.
*/}}
{{- define "unigate-common.hpa" -}}
{{- if .Values.autoscaling.enabled -}}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "unigate-common.fullname" . }}-hpa
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "unigate-common.fullname" . }}-deploy
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    {{- with .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ . }}
    {{- end }}
    {{- with .Values.autoscaling.targetMemoryUtilizationPercentage }}
    {{- /*
      ⚠️ JVM 앱에 메모리 기반 HPA 는 대개 함정이다. 힙은 GC 전까지 반납되지 않아
      사용률이 부하와 무관하게 높은 채로 머물고, 그러면 HPA 가 **줄이지 못한다.**
      값을 넣지 않는 것이 기본이며, 넣는다면 그 이유를 values 에 적는다.
    */}}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ . }}
    {{- end }}
  {{- with .Values.autoscaling.behavior }}
  behavior:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end -}}
{{- end -}}

{{/*
PodDisruptionBudget.

HPA 로 파드가 여러 개가 되는 순간 필요해진다 — 노드 드레인이나 롤링 중
가용 파드가 0 이 되는 창을 막는다. replica 1 짜리에 minAvailable: 1 을 걸면
**드레인이 영원히 막히므로**, 기본은 비활성이고 HPA 를 켤 때 함께 켠다.
*/}}
{{- define "unigate-common.pdb" -}}
{{- if .Values.podDisruptionBudget.enabled -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "unigate-common.fullname" . }}-pdb
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
spec:
  selector:
    matchLabels:
      {{- include "unigate-common.selectorLabels" . | nindent 6 }}
  {{- with .Values.podDisruptionBudget.minAvailable }}
  minAvailable: {{ . }}
  {{- end }}
  {{- with .Values.podDisruptionBudget.maxUnavailable }}
  maxUnavailable: {{ . }}
  {{- end }}
{{- end -}}
{{- end -}}
