{{/*
공용 Deployment 정의.

4개 앱(gateway · iam · demo-be · demo-fe)이 같은 정의를 쓰고, 차이는 전부 values 로 낸다.
JVM 앱과 nginx 앱의 차이(javaOpts · secret · tmp 볼륨 성격)는 값의 유무로 갈린다.
*/}}
{{- define "unigate-common.deployment" -}}
{{- $config := default (dict) .Values.config -}}
{{- $configEnabled := and $config.enabled (or $config.files $config.properties) -}}
{{- $configMountPath := default "/config" $config.mountPath | trimSuffix "/" -}}
{{- $configEnvVar := default "SPRING_CONFIG_ADDITIONAL_LOCATION" $config.envVar -}}
{{- $tmp := default (dict) .Values.tmpVolume -}}
{{- $tmpEnabled := $tmp.enabled -}}
{{- $tmpPvc := default (dict) $tmp.persistentVolumeClaim -}}
{{- $tmpUsePvc := and $tmpEnabled $tmpPvc.enabled -}}
{{- $tmpPvcName := printf "%s-pvc" (default (printf "%s-tmp" (include "unigate-common.fullname" .)) $tmpPvc.name) -}}
{{- $otel := default (dict) .Values.otelAgent -}}
{{- $otelEnabled := $otel.enabled -}}
{{- $otelMountPath := default "/otel" $otel.mountPath -}}
{{- $otelJarInImage := default "/opentelemetry-javaagent.jar" $otel.jarPathInImage -}}
{{- $secretEnabled := .Values.envFromSecret -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "unigate-common.fullname" . }}-deploy
  namespace: {{ include "unigate-common.namespace" . }}
  labels:
    {{- include "unigate-common.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  {{- /*
    ⚠️ HPA 가 켜져 있으면 replicas 를 렌더하지 않는다.
    차트가 replicas 를 고정한 채로 배포되면 helm upgrade 때마다 HPA 가 조정해 둔 값이
    되돌아가고, HPA 가 다시 올리는 진동이 생긴다. 소유권은 한쪽만 가져야 한다.
  */}}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  {{- with .Values.revisionHistoryLimit }}
  revisionHistoryLimit: {{ . }}
  {{- end }}
  {{- with .Values.strategy }}
  strategy:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "unigate-common.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "unigate-common.selectorLabels" . | nindent 8 }}
        {{- with .Values.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- /*
        config 내용이 바뀌면 파드를 다시 굴린다. 이게 없으면 ConfigMap 만 갱신되고
        파드는 옛 내용을 계속 마운트한 채 남는다 — "설정을 바꿨는데 안 먹는다" 의 전형.
        차트 밖 Secret 은 여기서 해시를 계산할 수 없어 배포 스크립트가
        `podAnnotations` 에 secret 해시를 넣어 같은 일을 한다.
      */}}
      {{- if or $configEnabled .Values.podAnnotations }}
      annotations:
        {{- if $configEnabled }}
        checksum/config: {{ toYaml $config.files | sha256sum }}
        {{- end }}
        {{- with .Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- end }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      serviceAccountName: {{ include "unigate-common.serviceAccountName" . }}
      {{- with .Values.terminationGracePeriodSeconds }}
      terminationGracePeriodSeconds: {{ . }}
      {{- end }}
      {{- with .Values.podSecurityContext }}
      securityContext:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- if $otelEnabled }}
      initContainers:
        - name: otel-agent-init
          image: {{ $otel.image | quote }}
          imagePullPolicy: {{ default "IfNotPresent" $otel.pullPolicy }}
          command: ["sh", "-c"]
          args:
            - cp {{ $otelJarInImage }} {{ $otelMountPath }}/{{ base $otelJarInImage }}
          volumeMounts:
            - name: otel-agent
              mountPath: {{ $otelMountPath }}
          {{- with $otel.resources }}
          resources:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          securityContext:
            runAsNonRoot: true
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
            seccompProfile:
              type: RuntimeDefault
      {{- end }}
      containers:
        - name: {{ include "unigate-common.name" . }}
          image: {{ include "unigate-common.image" . | quote }}
          imagePullPolicy: {{ include "unigate-common.imagePullPolicy" . }}
          {{- with .Values.command }}
          command:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- with .Values.args }}
          args:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          ports:
            - name: http
              containerPort: {{ .Values.service.targetPort }}
              protocol: TCP
          env:
            {{- with .Values.javaOpts }}
            - name: JAVA_OPTS
              value: {{ . | quote }}
            {{- end }}
            {{- range $key, $value := .Values.env }}
            - name: {{ $key }}
              value: {{ $value | quote }}
            {{- end }}
            {{- if $configEnabled }}
            - name: {{ $configEnvVar }}
              value: {{ printf "%s/" $configMountPath | quote }}
            {{- end }}
          {{- if $secretEnabled }}
          envFrom:
            - secretRef:
                {{- /*
                  차트가 만들지 않은 Secret 을 참조한다. 이름이 틀리면 파드가
                  CreateContainerConfigError 로 멈춘다 — optional: false 가 기본이라
                  **조용히 빈 값으로 뜨지 않는다.** 의도한 동작이다.
                */}}
                name: {{ include "unigate-common.secretName" . }}
          {{- end }}
          {{- with .Values.lifecycle }}
          lifecycle:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- if or $configEnabled $tmpEnabled $otelEnabled }}
          volumeMounts:
            {{- if $configEnabled }}
            - name: application-config
              mountPath: {{ $configMountPath }}
              readOnly: true
            {{- end }}
            {{- if $tmpEnabled }}
            - name: tmp-storage
              mountPath: {{ default "/tmp" $tmp.mountPath }}
            {{- end }}
            {{- if $otelEnabled }}
            - name: otel-agent
              mountPath: {{ $otelMountPath }}
              readOnly: true
            {{- end }}
          {{- end }}
          {{- with .Values.startupProbe }}
          startupProbe:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- with .Values.livenessProbe }}
          livenessProbe:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- with .Values.readinessProbe }}
          readinessProbe:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- with .Values.securityContext }}
          securityContext:
            {{- toYaml . | nindent 12 }}
          {{- end }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- if or $configEnabled $tmpEnabled $otelEnabled }}
      volumes:
        {{- if $configEnabled }}
        - name: application-config
          configMap:
            name: {{ include "unigate-common.fullname" . }}-cm
            {{- with $config.defaultMode }}
            defaultMode: {{ . }}
            {{- end }}
        {{- end }}
        {{- if $tmpEnabled }}
        - name: tmp-storage
          {{- if $tmpUsePvc }}
          {{- /*
            ⚠️ PVC 를 쓰면 ReadWriteOnce 특성상 파드가 노드를 넘어 늘어날 수 없다.
            HPA 를 켜는 앱에서는 반드시 emptyDir 여야 한다 — 두 번째 파드가 다른 노드에
            스케줄되는 순간 Pending 에 걸리고, 증상은 "HPA 가 안 뜬다" 로만 보인다.
          */}}
          persistentVolumeClaim:
            claimName: {{ $tmpPvcName }}
          {{- else }}
          emptyDir:
            {{- with $tmp.sizeLimit }}
            sizeLimit: {{ . }}
            {{- end }}
          {{- end }}
        {{- end }}
        {{- if $otelEnabled }}
        - name: otel-agent
          emptyDir: {}
        {{- end }}
      {{- end }}
{{- end -}}
