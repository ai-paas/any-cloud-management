{{/*
공통 helper. fullname / labels / selectorLabels / serviceAccountName 등 표준 패턴.
*/}}

{{- define "cluster-agent.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-agent.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "cluster-agent.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cluster-agent.labels" -}}
helm.sh/chart: {{ include "cluster-agent.chart" . }}
{{ include "cluster-agent.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/component: agent
{{- end -}}

{{- define "cluster-agent.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cluster-agent.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* secret 이름 — externalSecret 우선, 없으면 chart 가 만드는 default 이름 */}}
{{- define "cluster-agent.bootstrapSecretName" -}}
{{- if .Values.bootstrap.existingSecret -}}
{{- .Values.bootstrap.existingSecret -}}
{{- else -}}
aipaas-agent-bootstrap
{{- end -}}
{{- end -}}

{{/* installer SA 가 비활성이면 core SA 로 fallback (1개 pod 가 helm 도 수행) */}}
{{- define "cluster-agent.serviceAccountName" -}}
{{- if .Values.rbac.installer.enabled -}}
{{- .Values.rbac.installer.serviceAccountName -}}
{{- else if .Values.rbac.core.enabled -}}
{{- .Values.rbac.core.serviceAccountName -}}
{{- else -}}
default
{{- end -}}
{{- end -}}

{{/*
공통 pod template — single/core/installer 모두 사용.
context dict:
  Values, Release, Chart : root 의 sub-tree
  podRole                : "single" | "core" | "installer" (label 용)
  serviceAccountName     : SA 이름
  agentMode              : AGENT_MODE env value
*/}}
{{- define "cluster-agent.podTemplate" -}}
{{- $values := .Values -}}
{{- $release := .Release -}}
metadata:
  labels:
{{ include "cluster-agent.selectorLabels" (dict "Values" $values "Release" $release "Chart" .Chart) | indent 4 }}
    app.kubernetes.io/component: agent
{{- if ne .podRole "single" }}
    aipaas.io/agent-role: {{ .podRole }}
{{- end }}
  annotations:
    "anycloud.aipaas/backend-grpc-addr": {{ $values.backend.grpcAddr | default "PENDING_INJECT" | quote }}
spec:
  serviceAccountName: {{ .serviceAccountName }}
  automountServiceAccountToken: true
  {{- with $values.image.pullSecrets }}
  imagePullSecrets:
{{ toYaml . | indent 4 }}
  {{- end }}
  securityContext:
{{ toYaml $values.podSecurityContext | indent 4 }}
  containers:
  - name: agent
    image: "{{ $values.image.repository }}:{{ $values.image.tag }}"
    imagePullPolicy: {{ $values.image.pullPolicy }}
    env:
    - name: AGENT_MODE
      value: {{ .agentMode | quote }}
    - name: BACKEND_GRPC_ADDR
      value: {{ required "backend.grpcAddr 가 비어있음 — backend 의 agent.grpc.public-endpoint 설정 또는 values.backend.grpcAddr 지정 필요" $values.backend.grpcAddr | quote }}
    {{- if $values.backend.tls.enabled }}
    - name: BACKEND_GRPC_TLS_ENABLED
      value: "true"
    {{- if $values.backend.tls.caCert }}
    - name: BACKEND_CA_CERT_PEM
      value: {{ $values.backend.tls.caCert | quote }}
    {{- end }}
    {{- if $values.backend.tls.serverName }}
    - name: BACKEND_TLS_SERVER_NAME
      value: {{ $values.backend.tls.serverName | quote }}
    {{- end }}
    {{- if $values.backend.tls.insecureSkipVerify }}
    - name: BACKEND_TLS_INSECURE_SKIP_VERIFY
      value: "true"
    {{- end }}
    {{- end }}
    - name: REGISTRATION_TOKEN
      valueFrom:
        secretKeyRef:
          name: {{ include "cluster-agent.bootstrapSecretName" (dict "Values" $values) }}
          key: {{ $values.bootstrap.secretKey }}
    - name: POD_NAME
      valueFrom:
        fieldRef:
          fieldPath: metadata.name
    - name: AGENT_INSTANCE_ID
      valueFrom:
        fieldRef:
          fieldPath: metadata.uid
    - name: AGENT_LEADER_ELECTION
      value: {{ $values.agent.leaderElection | quote }}
    - name: AGENT_NAMESPACE
      value: {{ $values.agent.namespace | quote }}
    - name: HOME
      value: /tmp
    {{- with $values.agent.extraEnv }}
{{ toYaml . | indent 4 }}
    {{- end }}
    securityContext:
{{ toYaml $values.containerSecurityContext | indent 6 }}
    resources:
{{ toYaml $values.resources | indent 6 }}
    volumeMounts:
    - name: tmp
      mountPath: /tmp
  volumes:
  - name: tmp
    emptyDir:
      sizeLimit: {{ $values.agent.tmpVolume.sizeLimit | default "128Mi" }}
  {{- with $values.nodeSelector }}
  nodeSelector:
{{ toYaml . | indent 4 }}
  {{- end }}
  {{- with $values.tolerations }}
  tolerations:
{{ toYaml . | indent 4 }}
  {{- end }}
  {{- with $values.affinity }}
  affinity:
{{ toYaml . | indent 4 }}
  {{- end }}
{{- end -}}
