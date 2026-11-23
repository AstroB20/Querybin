{{- define "api-builder.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "api-builder.builderName" -}}
{{- printf "%s-builder" (include "api-builder.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "api-builder.runtimeName" -}}
{{- printf "%s-runtime" (include "api-builder.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "api-builder.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- printf "%s-secrets" (include "api-builder.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "api-builder.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/part-of: api-builder
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "api-builder.targetDbEnv" -}}
- name: SPRING_DATASOURCE_URL
  value: {{ required "targetDatabase.url is required" .Values.targetDatabase.url | quote }}
- name: SPRING_DATASOURCE_USERNAME
  value: {{ required "targetDatabase.username is required" .Values.targetDatabase.username | quote }}
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "api-builder.secretName" . }}
      key: targetDbPassword
      optional: true
- name: APP_TARGET_SCHEMA
  value: {{ .Values.targetDatabase.schema | quote }}
{{- end -}}
