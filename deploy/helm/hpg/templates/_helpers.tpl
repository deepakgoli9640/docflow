{{/* Common names and labels. */}}
{{- define "hpg.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}{{ .Release.Name | trunc 40 | trimSuffix "-" }}{{- else -}}{{ printf "%s-%s" .Release.Name .Chart.Name | trunc 40 | trimSuffix "-" }}{{- end -}}
{{- end -}}

{{/* Name of one component: <fullname>-<service>. Call with (dict "root" $ "name" "payment-service"). */}}
{{- define "hpg.component" -}}
{{ printf "%s-%s" (include "hpg.fullname" .root) .name | trunc 63 | trimSuffix "-" }}
{{- end -}}

{{- define "hpg.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
app.kubernetes.io/part-of: hpg
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/version: {{ .root.Values.image.tag | quote }}
{{ include "hpg.selectorLabels" . }}
{{- end -}}

{{- define "hpg.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/* Effective settings of one service: the service's own values merged over the defaults. */}}
{{- define "hpg.settings" -}}
{{- $d := deepCopy .root.Values.defaults -}}
{{- toYaml (mergeOverwrite $d (deepCopy .svc)) -}}
{{- end -}}
