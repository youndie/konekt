# The server image, from a distribution built outside it.
#
# Not a multi-stage build with Gradle inside. Two reasons, and the second is the one that bites: a
# build stage re-downloads the Kotlin toolchain and every dependency on any cache miss, and the
# distribution this repository produces is already the artefact CI tests — building it a second way
# inside an image is a second thing that can differ from what was tested.
#
# So the prerequisite is explicit rather than implicit:
#
#     ./gradlew :server:installDist && docker compose -f deploy/compose.yaml up
#
# A JRE, and version 25 specifically. The toolchain that COMPILED this is about compilation; what a
# class file needs to RUN is a JVM at least as new, and a distribution handed a 21 runtime fails with
# UnsupportedClassVersionError at exec rather than at build.
FROM eclipse-temurin:25-jre

WORKDIR /opt/konekt

# The whole distribution: start scripts and the jars beside them. Copied as one directory rather than
# assembled here, so what runs is what `installDist` produced.
COPY server/build/install/server/ ./

# A non-root user, because the process needs nothing a root one has.
RUN useradd --system --uid 10001 konekt && chown -R konekt:konekt /opt/konekt
USER konekt

EXPOSE 8080

# The health route exists so this can ask the process a question rather than ask the kernel whether a
# port accepts — the kernel accepts into the backlog with no help from a hung process.
#
# `bash` AND NOT `sh`, and this was wrong until a stand tried to wait on it. `/bin/sh` here is dash,
# which has no `/dev/tcp` — the check answered "cannot create /dev/tcp/127.0.0.1/8080: Directory
# nonexistent" on every run, so the container was permanently unhealthy while the process inside it
# was serving. Nothing depended on the healthcheck until `depends_on: service_healthy` did, which is
# why it went unnoticed: an unhealthy container that nobody waits for looks exactly like a healthy one.
# LIVENESS, NAMED, and the body it greps for changed with it. `/health` still answers — kore mounts
# it as the liveness alias so a chart migrates a line at a time — but it answers `alive` rather than
# `ok`, and a healthcheck grepping the old word would have failed here forever. The stand waits on
# this through `service_healthy`, so it would not have been a quiet failure; it would have been a
# stand that never comes up.
#
# Pointed at the explicit route rather than the alias, because this file is not a chart anybody has to
# migrate carefully: it is ours, and naming the question is worth more than keeping the old address.
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=10 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health/live HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q alive <&3"]

ENTRYPOINT ["./bin/server"]
