# Cold start with and without the AOT cache, the Jib image — 2026-09-07

`scripts/measure/aot-coldstart-jib.sh 5 2` on the build box (Ubuntu 24.04 in WSL2, Core Ultra 7
255HX, Docker 29.1.3), the stand from `deploy/compose.yaml` + `deploy/compose.measure.yaml` (the
server under the chart's limits, `cpu: 1`, `memory: 1G`). The server as a Jib image
(`server/build.gradle.kts`, `jib { }`): base `eclipse-temurin:25-jre` (Temurin 25.0.4+7),
`containerizingMode = "packaged"`, user 10001. `konekt-server-jib:nocache` is the first Jib build;
`konekt-server-jib:latest` is the second, with the cache zavarnik's `jibAotTrain` trained inside a
container of the first on the stand's network, as one more layer and `-XX:AOTCache` in the
entrypoint. See `research-measurements.md` §6a.

| File | What |
|---|---|
| `experiment.log` | the script's own lines: the entrypoint, the two image sizes, every restart |
| `jibAotVerify.txt` | the runner's verification inside `konekt-server-jib:latest` under `-XX:AOTMode=on` |
| `coldstart-nocache-{1,2}.csv` | five restarts each of the image without the cache, rounds 1 and 2 |
| `coldstart-latest-{1,2}.csv` | the same for the image with the cache; rounds alternate: nocache, latest, nocache, latest |

Columns are `coldstart.sh`'s: restart, `docker start` → `/health` in ms, the first home-screen
request in ms, then p50, p95 and max of the first hundred.
