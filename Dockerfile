# syntax=docker/dockerfile:1

# =============================================================================
# Stage 1 — build
# =============================================================================
# The previous build ran `apk add --no-cache maven`, which meant the Maven
# version was whatever Alpine's repository happened to serve on the day of the
# build — different from what any developer or CI ran, and silently changing
# over time. The repository now carries a Maven Wrapper, so this stage builds
# with the exact Maven version pinned in .mvn/wrapper/maven-wrapper.properties,
# verified against a recorded SHA-256. Same Maven here, in CI and on a laptop.
FROM eclipse-temurin:25-jdk-alpine AS build

# The wrapper's only-script distribution downloads and unpacks Maven itself.
# Alpine's busybox provides wget but not unzip, so unzip is the one build-only
# package needed. It never reaches the runtime image.
RUN apk add --no-cache unzip

WORKDIR /build

# --- Dependency layer ------------------------------------------------------
# The pom and the wrapper are copied on their own, and dependencies resolved,
# BEFORE any source. Docker caches this layer and reuses it for every build
# where pom.xml has not changed, so editing a .java file no longer re-downloads
# the entire Spring Boot dependency tree. The old `COPY . .` made that
# impossible: any change to any file invalidated the dependency download.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

# --- Source layer ----------------------------------------------------------
COPY src/ src/

# Tests are run by CI (`./mvnw verify`) against the same source, so re-running
# them here would double every build's cost without adding a signal. The image
# is built from a commit CI has already proven.
RUN ./mvnw -B -ntp clean package -DskipTests

# Normalise the artifact name. The previous Dockerfile hardcoded
# `backend-0.0.1-SNAPSHOT.jar`, so the first version bump in pom.xml would have
# broken the image build with a confusing "file not found" — and, worse, the
# next line's wildcard is checked here so a build producing zero or several
# jars fails loudly instead of copying the wrong one.
RUN set -eu; \
    jars="$(find target -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' ! -name '*.original')"; \
    [ "$(echo "$jars" | wc -l)" -eq 1 ] || { echo "expected exactly one jar, got:"; echo "$jars"; exit 1; }; \
    cp "$jars" /build/app.jar

# =============================================================================
# Stage 2 — runtime
# =============================================================================
# A JRE, not a JDK. The previous runtime stage shipped the full JDK — compiler,
# javac, jlink, debugging tools — into production for no reason. Every one of
# those is attack surface that the running application never uses.
FROM eclipse-temurin:25-jre-alpine AS runtime

# Run as a non-root user. Previously the application ran as root, so a remote
# code execution would have started with uid 0 inside the container. `app` owns
# nothing it does not need: the jar is root-owned and read-only to it.
RUN addgroup -S -g 1001 app && adduser -S -u 1001 -G app -h /app app

WORKDIR /app

# --- Uploads ---------------------------------------------------------------
# Declared and owned here so the mounted volume is writable by uid 1001. The
# application resolves "uploads" relative to its working directory, so this is
# the exact path a production volume must mount over.
RUN mkdir -p /app/uploads && chown app:app /app/uploads

# Root-owned, mode 0444: the process can read its own jar but cannot rewrite it.
COPY --from=build --chown=root:root --chmod=444 /build/app.jar /app/app.jar

USER app

# Documents the port the production profile binds (application-prod.properties).
# Publishing is the deployment's decision — in production only Caddy reaches
# this port, over the private Docker network.
EXPOSE 8080

# Containers get a cgroup memory limit, not the host's RAM. These flags make the
# JVM honour it — without MaxRAMPercentage the heap is sized from the host's
# 2 GiB while Postgres, Redis and Caddy are also using it.
# The image's own contract, matching EXPOSE above: this container serves on 8080,
# whatever Spring profile is active. Without it the default profile binds 8081
# (the port the frontend dev server proxies to) and both the published port and
# the healthcheck below reach nothing.
ENV SERVER_PORT=8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Reports the container unhealthy when the application cannot serve. It asks the
# readiness probe, not liveness, because readiness is what accounts for Postgres
# and Redis — a JVM that is up but cannot reach its database is not ready, and
# without this the container would look perfectly healthy while failing every
# request.
#
# start-period covers JVM start plus Flyway migrations, and failures during it do
# not count against retries, so a slow start is not mistaken for a broken one.
# The 10 s timeout sits at the production datasource connection timeout
# (application-prod.properties), so a database that is slow to answer is reported
# by the health indicator rather than cut off by wget first.
# busybox wget is already present in the base image; nothing is installed for this.
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health/readiness" | grep -q '"status":"UP"' || exit 1

# Exec form, no shell: the JVM becomes PID 1 and receives SIGTERM directly, so
# `docker stop` and compose restarts shut it down gracefully instead of killing
# it after the 10 s timeout.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
