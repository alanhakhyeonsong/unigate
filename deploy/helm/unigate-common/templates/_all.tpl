{{/*
앱 차트가 한 줄로 끌어다 쓰는 진입점.

앱 차트의 `templates/` 에는 이 include 한 줄만 둔다 — 어떤 리소스가 나오는지는
전부 values 가 결정하므로, 앱마다 템플릿을 복제하면 그 복제본들이 서서히 갈라진다.

`---` 로 구분된 빈 문서는 Helm 이 무시한다(비활성 리소스는 빈 문자열을 낸다).
*/}}
{{- define "unigate-common.all" -}}
{{ include "unigate-common.serviceaccount" . }}
---
{{ include "unigate-common.configmap" . }}
---
{{ include "unigate-common.pvc" . }}
---
{{ include "unigate-common.deployment" . }}
---
{{ include "unigate-common.service" . }}
---
{{ include "unigate-common.ingress" . }}
---
{{ include "unigate-common.hpa" . }}
---
{{ include "unigate-common.pdb" . }}
{{- end -}}
