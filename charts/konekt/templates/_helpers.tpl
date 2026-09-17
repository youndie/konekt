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
- name: KONEKT_DB_URL
  value: {{ include "konekt.dbUrl" . | quote }}
- name: KONEKT_DB_USER
  value: {{ .Values.postgres.user | quote }}
- name: KONEKT_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-secrets
      key: postgres-password
- name: KONEKT_JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .Release.Name }}-secrets
      key: jwt-secret
{{- end }}

{{/*
THE JVM'S CEILINGS, AND THE ONE ARITHMETIC MISTAKE THAT IS WORTH STOPPING A RENDER FOR (`B-127`).

`server.jvmOptions` bounds the heap, the code cache and direct memory; `server.resources.limits.memory`
bounds the container. Nothing connects the two, and the failure when they disagree is the quiet kind:
the pod is OOM-killed under load, the event says `OOMKilled`, and the number that was wrong is in
another file. So the render does the sum.

WHAT IS IN THE SUM AND WHAT IS DELIBERATELY NOT. The three ceilings above are memory the JVM will
USE if it needs it, so they are added. `MaxMetaspaceSize` is not: with the AOT cache accepted,
metaspace holds about 7 MiB and the ceiling is there for the case where the cache is refused and the
classes load the ordinary way (`server.jvmOptions` says why). Adding a reserve for the bad case to
every deployment's limit would make the good case pay for it.

THE HEADROOM IS MEASURED AND IS BIGGER THAN IT LOOKS. Under the reading profile at 200 rps the
clamped JVM held 196 MiB of anonymous memory while its heap was using 63 and its code cache 32 — so
about 100 MiB is neither. Most of that is the AOT cache: the archive is mapped private, and every
page the JVM relocates on the way in becomes anonymous, which is why a 62 MiB `Shared class space`
shows up in the cgroup as memory this process holds. Thread stacks, GC structures and symbols are
the rest. The constant is that 100 rounded down to 96, and it is a floor taken from one run rather
than a law — see `docs/research/measurements-2026-09-17/memory/`.
*/}}
{{- define "konekt.jvmEnv" -}}
{{- $opts := .Values.server.jvmOptions | default "" }}
{{- $limit := .Values.server.resources.limits.memory | toString }}
{{- $mib := 0 }}
{{- if hasSuffix "Gi" $limit }}{{ $mib = mul (atoi (trimSuffix "Gi" $limit)) 1024 }}{{ else if hasSuffix "Mi" $limit }}{{ $mib = atoi (trimSuffix "Mi" $limit) }}{{ end }}
{{- $ceilings := 0 }}
{{- range $flag := list "-Xmx" "-XX:ReservedCodeCacheSize=" "-XX:MaxDirectMemorySize=" }}
{{- $found := regexFind (printf "%s[0-9]+[MmGg]" $flag) $opts }}
{{- if $found }}
{{- $size := trimPrefix $flag $found }}
{{- if or (hasSuffix "G" $size) (hasSuffix "g" $size) }}{{ $ceilings = add $ceilings (mul (atoi (trimSuffix "g" (trimSuffix "G" $size))) 1024) }}{{ else }}{{ $ceilings = add $ceilings (atoi (trimSuffix "m" (trimSuffix "M" $size))) }}{{ end }}
{{- end }}
{{- end }}
{{- if and (gt $mib 0) (gt (add $ceilings 96) $mib) }}
{{- fail (printf "konekt: server.resources.limits.memory is %s and server.jvmOptions already promises the JVM %d MiB of heap, code cache and direct memory. With the 96 MiB the runtime itself needs — thread stacks, GC structures, symbols — this pod is OOM-killed under load, and the event will say OOMKilled while the number that is wrong is in values.yaml. Raise the limit or lower a ceiling" $limit (int $ceilings)) }}
{{- end }}
{{- if $opts }}
- name: JAVA_TOOL_OPTIONS
  value: {{ $opts | trim | quote }}
{{- end }}
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
- name: KONEKT_{{ .name | upper }}_ENDPOINT
  value: {{ $agent.endpoint | quote }}
- name: KONEKT_{{ .name | upper }}_KEY
  valueFrom:
    secretKeyRef:
      name: {{ .root.Release.Name }}-secrets
      key: {{ .name }}-key
{{- end }}
{{- end }}
