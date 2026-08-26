{{/*
Refusals, in one place so that a missing value stops the render rather than the pod.

The distinction this chart draws is between what is absent and what is wrong. An absent hostname
renders an ingress rule that matches nothing; an absent image tag leaves helm nothing to notice, so
a green deploy runs the previous binary; an absent JWT secret or database password stops the server
from starting at all, which arrives as a successful deploy and a service that is down. None of the
four fails loudly on its own, so all four fail here.
*/}}
{{- define "konekt.require" -}}
{{- if not .value }}{{ fail (printf "konekt: %s is required — %s" .name .because) }}{{ end }}
{{- .value }}
{{- end }}

{{/*
The database address, spelled once. Both the server and the migration that runs before it read it,
and two spellings of one address is how a deployment comes to migrate one database and serve
another.
*/}}
{{- define "konekt.dbUrl" -}}
jdbc:postgresql://{{ .Release.Name }}-postgres:5432/{{ .Values.postgres.database }}
{{- end }}

{{/*
Everything the server and the migration BOTH need, so that they cannot disagree.

They are one image with a switch — `MIGRATE_ONLY` — and the migration runs as this pod's init
container. What makes that safe is that it is handed exactly these values: a migration pointed at a
different database, or refusing to start because it was handed no JWT secret, are both failures that
happen after the deploy has reported success.
*/}}
{{- define "konekt.dbEnv" -}}
- name: DB_URL
  value: {{ include "konekt.dbUrl" . | quote }}
- name: DB_USER
  value: {{ .Values.postgres.user | quote }}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-secrets
      key: postgres-password
- name: JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-secrets
      key: jwt-secret
{{- end }}

{{/*
An agent's pair of variables, or neither.

`endpoint` without `key` is refused by the server at startup and by this template before that — the
message here names the value in a file somebody can edit, while the server's names an environment
variable in a pod that is already failing.
*/}}
{{- define "konekt.agentEnv" -}}
{{- $agent := .agent }}
{{- if and $agent.endpoint (not $agent.key) }}
{{- fail (printf "konekt: observability.%s.endpoint is set and observability.%s.key is empty — the server refuses to start on one without the other, because a deployment that believes it is observed and is silent looks exactly like one that is working" .name .name) }}
{{- end }}
{{- if and $agent.key (not $agent.endpoint) }}
{{- fail (printf "konekt: observability.%s.key is set and observability.%s.endpoint is empty — a key with nowhere to send it observes nothing" .name .name) }}
{{- end }}
{{- if $agent.endpoint }}
- name: {{ .name | upper }}_ENDPOINT
  value: {{ $agent.endpoint | quote }}
- name: {{ .name | upper }}_KEY
  valueFrom:
    secretKeyRef:
      name: {{ .root.Release.Name }}-secrets
      key: {{ .name }}-key
{{- end }}
{{- end }}
