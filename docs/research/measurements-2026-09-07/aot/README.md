# Cold start with and without the AOT cache — 2026-09-07

`scripts/measure/aot-coldstart.sh 5 2` on the build box (Ubuntu 24.04 in WSL2, Core Ultra 7 255HX,
Docker 29.1.3), the stand from `deploy/compose.yaml` + `deploy/compose.measure.yaml` (the server
under the chart's limits, `cpu: 1`, `memory: 1G`), image `konekt-server:local` built from this
branch, base `eclipse-temurin:25-jre` (Temurin 25.0.4+7). See `research-measurements.md` §6.

| File | What |
|---|---|
| `experiment.log` | the script's own lines: training inside the image, the two image sizes, the verification, every restart |
| `verify.txt` | the runner's verification of the cache under `-XX:AOTMode=on` inside `konekt-server:local-aot` |
| `coldstart-local-{1,2}.csv` | five restarts each of the image without the cache, rounds 1 and 2 |
| `coldstart-local-aot-{1,2}.csv` | the same for the image with the cache; rounds alternate: local, aot, local, aot |

Columns are `coldstart.sh`'s: restart, `docker start` → `/health` in ms, the first home-screen
request in ms, then p50, p95 and max of the first hundred.
