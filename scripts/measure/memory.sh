#!/usr/bin/env bash
# WHAT THE SERVER ACTUALLY NEEDS, AND WHAT A CLAMPED JVM COSTS (`B-127`).
#
#     scripts/measure/memory.sh 3                    # the default pair, three rounds
#     IMAGE=konekt-server:mem-base-aot scripts/measure/memory.sh 3 variants.txt
#
# One line of CSV per (round, variant): cold start, anonymous memory at rest and under the reading
# profile, the latency k6 saw, and what the JVM said about its own memory on the way out.
#
# THREE THINGS IN HERE ARE THE MEASUREMENT RATHER THAN THE HARNESS, and each was a wrong number
# first:
#
#   * **`anon` from `memory.stat`, not `memory.current`.** The latter counts the page cache, and
#     this image maps a 64 MiB AOT cache — so by `memory.current` every variant "approaches its
#     limit" while consuming nothing. The load study of a neighbouring service on this stack made
#     the same mistake first.
#   * **The variants alternate inside a round** rather than running as two blocks, because a box
#     that accumulates state drifts monotonically and a block ordering hands the drift to one of
#     them.
#   * **The JVM's own breakdown comes from `-XX:+PrintNMTStatistics` at exit**, not from `jcmd`:
#     the runtime image is a JRE and carries no `jcmd`. The variant asks for it by including
#     `-XX:NativeMemoryTracking=summary`; without that flag the columns are empty rather than wrong.
#
# The generator runs on the same box as the subject, which protects the subject: k6 gets its own
# cores but not its own machine, so the latency columns here are a smoke test for "the clamp did not
# break it" and NOT the latency numbers the report quotes — those come from `k6.sh` on the two-box
# stand.
set -euo pipefail
cd "$(dirname "$0")/../.."

ROUNDS=${1:-3}
VARIANTS_FILE=${2:-}
IMAGE=${IMAGE:-konekt-server:mem-base-aot}
PROJECT=${PROJECT:-konekt-mem}
RATES=${RATES:-25,200}
HOLD=${HOLD:-60}
SUBSCRIBERS=${SUBSCRIBERS:-30}
SETTLE=${SETTLE:-30}
MEASURE_HOME=${MEASURE_HOME:-${XDG_STATE_HOME:-$HOME/.local/state}/konekt-measure}
OUT=$MEASURE_HOME/out
mkdir -p "$OUT"
CSV=$OUT/memory.csv
SERVER=${PROJECT}-server-1

# name|container limit|JAVA_TOOL_OPTIONS — the pair the report compares, unless a file says otherwise.
DEFAULT_VARIANTS=(
  "base|1G|"
  "clamp|320M|-XX:+UseSerialGC -XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M -Xss256k -XX:MaxMetaspaceSize=80M -Xmx64M -XX:+ExitOnOutOfMemoryError"
)
if [ -n "$VARIANTS_FILE" ]; then
  mapfile -t VARIANTS < <(grep -v '^[[:space:]]*#' "$VARIANTS_FILE" | grep -v '^[[:space:]]*$')
else
  VARIANTS=("${DEFAULT_VARIANTS[@]}")
fi

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
# The container's own cgroup, read from inside it: `anon` is what this process actually holds, and
# `file` beside it is the AOT cache and the jars, which the kernel reclaims under pressure.
anon_mib() {
  docker exec "$SERVER" sh -c "awk '/^anon /{print int(\$2/1048576)}' /sys/fs/cgroup/memory.stat" 2>/dev/null || echo ""
}
file_mib() {
  docker exec "$SERVER" sh -c "awk '/^file /{print int(\$2/1048576)}' /sys/fs/cgroup/memory.stat" 2>/dev/null || echo ""
}

echo "# image=$IMAGE rates=$RATES hold=$HOLD subscribers=$SUBSCRIBERS rounds=$ROUNDS" > "$CSV"
echo "round,variant,limit,start_to_ready_ms,anon_idle_mib,anon_peak_mib,anon_after_mib,file_mib,k6_p95_ms,k6_failed,gc_pauses,gc_max_ms,nmt_heap_mib,nmt_class_mib,nmt_code_mib,nmt_thread_mib,nmt_shared_mib,nmt_committed_mib,oom" >> "$CSV"

for round in $(seq 1 "$ROUNDS"); do
  for spec in "${VARIANTS[@]}"; do
    IFS='|' read -r name limit opts <<< "$spec"
    echo "== round $round, variant $name (limit $limit)"

    ENV_FILE=$MEASURE_HOME/$name.env
    OVERRIDE=$MEASURE_HOME/$name.yaml
    cat > "$ENV_FILE" <<ENV
SERVER_IMAGE=$IMAGE
RELEASE=$IMAGE
SIMULATE_TRAFFIC=true
ENV
    # The GC log comes from `compose.measure.yaml`, layered below: every variant wants it — a heap
    # that is too small shows up as pause count long before it shows up as a failed request — and it
    # is the measurement stand's property rather than this variant's.
    cat > "$OVERRIDE" <<YAML
services:
  server:
    environment:
      JAVA_TOOL_OPTIONS: "$opts"
    deploy:
      resources:
        limits:
          cpus: "1"
          memory: $limit
YAML
    # `compose.measure.yaml` is layered for what it already carries: the GC log and the declining
    # server behind its profile, so a memory measurement does not run a second JVM beside the
    # subject. The variant's own file comes last and owns the limit.
    COMPOSE=(docker compose -p "$PROJECT" -f deploy/compose.yaml -f deploy/compose.measure.yaml -f "$OVERRIDE" --env-file "$ENV_FILE")

    "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
    "${COMPOSE[@]}" up -d --no-build --wait >/dev/null

    # COLD START ON A WARM BOX: the container is restarted rather than created, so what is measured
    # is the JVM coming up and not docker creating a network. Ready and not live — the question a
    # rollout asks.
    docker stop -t 20 "$SERVER" >/dev/null
    t0=$(now_ms)
    docker start "$SERVER" >/dev/null
    # BOUNDED, because a variant that cannot start is a result and not a reason to hang: the row is
    # written with -1 and the run goes on to the next variant.
    ready=-1
    for _ in $(seq 1 1200); do
      if curl -sf -o /dev/null http://127.0.0.1:8080/health/ready 2>/dev/null; then ready=$(( $(now_ms) - t0 )); break; fi
      docker inspect -f '{{.State.Running}}' "$SERVER" 2>/dev/null | grep -q true || break
      sleep 0.1
    done

    if [ "$ready" -lt 0 ]; then
      dead=$("${COMPOSE[@]}" logs --no-log-prefix server 2>/dev/null | grep -v 'Picked up JAVA_TOOL_OPTIONS' | grep -c -e OutOfMemoryError -e StackOverflowError || true)
      echo "$round,$name,$limit,-1,,,,,,,,,,,,,,,$dead" | tee -a "$CSV"
      "${COMPOSE[@]}" logs --no-log-prefix server 2>/dev/null | tail -15
      "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
      continue
    fi

    sleep "$SETTLE"
    idle=$(anon_mib)

    # The sampler runs for as long as the generator does; the peak is the highest sample, which is
    # a floor on the real peak rather than the peak itself. Two seconds is fine for a heap that
    # moves in minutes and useless for one that spikes in milliseconds — and a spike that fast is
    # what the OOM column is for.
    PEAK=$MEASURE_HOME/$name.peak
    : > "$PEAK"
    ( while sleep 2; do anon_mib >> "$PEAK" || true; done ) & sampler=$!

    K6OUT=$OUT/memory-$name-$round.json
    rm -f "$K6OUT"
    set +e
    MEASURE_HOME=$MEASURE_HOME PROJECT=$PROJECT NETWORK=${PROJECT}_default \
      scripts/measure/k6.sh screens "RATES=$RATES" "HOLD=$HOLD" "SUBSCRIBERS=$SUBSCRIBERS" \
      > "$OUT/memory-$name-$round.k6.log" 2>&1
    k6rc=$?
    set -e
    kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true

    peak=$(sort -n "$PEAK" | tail -1)
    after=$(anon_mib)
    files=$(file_mib)
    summary=$(ls -t "$OUT"/screens-*.json 2>/dev/null | head -1)
    if [ -n "$summary" ]; then
      p95=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(round(d["metrics"]["http_req_duration"]["p(95)"],1))' "$summary" 2>/dev/null || echo "")
      failed=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); m=d["metrics"].get("http_req_failed",{}); print(round(m.get("value",m.get("rate",0)),4))' "$summary" 2>/dev/null || echo "")
    else p95=""; failed=""; fi

    gcp=$(docker exec "$SERVER" sh -c 'grep -c "Pause" /tmp/gc.log 2>/dev/null' 2>/dev/null || echo 0)
    gcmax=$(docker exec "$SERVER" sh -c 'grep -o "[0-9.]*ms$" /tmp/gc.log 2>/dev/null | tr -d "ms" | sort -n | tail -1' 2>/dev/null || echo "")

    # THE JVM'S OWN ACCOUNT, printed as it dies. `stop` rather than `kill`, because the summary is
    # written on the way out and a SIGKILL takes it with it. A variant that did not ask for NMT
    # leaves these columns empty — empty, and not a zero that reads like a measurement.
    docker stop -t 30 "$SERVER" >/dev/null
    NMT=$OUT/nmt-$name-$round.log
    "${COMPOSE[@]}" logs --no-log-prefix server > "$NMT" 2>/dev/null || true
    # THE LAST DUMP AND NOT THE FIRST. `compose logs` for a service carries every container that ever
    # answered to it, and this run restarts the server once to time its start — so the first summary
    # in the file belongs to a JVM that served nothing. Sizes are bytes: this JDK prints them bare.
    last_nmt() { local n; n=$(grep -n 'Native Memory Tracking:' "$NMT" | tail -1 | cut -d: -f1); [ -n "$n" ] && tail -n +"$n" "$NMT"; }
    nmt() { last_nmt | grep -E -- "$1" | head -1 | grep -o 'committed=[0-9]*' | head -1 | tr -dc '0-9' | awk 'NF{print int($1/1048576)}'; }
    heapm=$(nmt '^- +Java Heap'); classm=$(nmt '^- +Class'); codem=$(nmt '^- +Code'); threadm=$(nmt '^- +Thread')
    sharedm=$(nmt '^- +Shared class space')
    totalm=$(last_nmt | grep -o 'Total: reserved=[0-9]*, committed=[0-9]*' | tail -1 | grep -o 'committed=[0-9]*' | tr -dc '0-9' | awk 'NF{print int($1/1048576)}')
    # THE FLAG'S OWN ECHO IS NOT AN OOM. `-XX:+ExitOnOutOfMemoryError` is printed back by the JVM
    # on its first line — `Picked up JAVA_TOOL_OPTIONS: …` — once per container start, so a grep for
    # the word scored every clamped variant as two OOMs and every unclamped one as none. A column
    # that reads 2 for the healthy runs and 0 for the risky ones is worse than no column.
    oom=$(grep -v 'Picked up JAVA_TOOL_OPTIONS' "$NMT" 2>/dev/null | grep -c -e OutOfMemoryError -e StackOverflowError || true)

    echo "$round,$name,$limit,$ready,$idle,$peak,$after,$files,$p95,$failed,$gcp,$gcmax,$heapm,$classm,$codem,$threadm,$sharedm,$totalm,$oom" | tee -a "$CSV"
    [ "$k6rc" -eq 0 ] || echo "  (k6 exited $k6rc — see $OUT/memory-$name-$round.k6.log)"
    "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
  done
done

echo
echo "$CSV:"
column -s, -t < "$CSV"
