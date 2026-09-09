# syntax=docker/dockerfile:1.7

# --- Build stage: compile the fat jar with Maven + JDK 21 ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# --- Runtime stage: JRE only, non-root user ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1000 --create-home --home-dir /home/worker worker

WORKDIR /app
COPY --from=build /build/target/llm-worker.jar app.jar
RUN chown worker:worker app.jar
USER worker

# --- Required, no default -- WorkerProperties fails startup fast without these:
# GATEWAY_URL, GATEWAY_API_KEY (must equal the Gateway's WORKER_TOKEN), WORKER_ID, BACKEND_ID
# (must match a `name` row in the Gateway's `backends` table), LLAMA_MODEL.
# Set these at `docker run -e ...` / compose / k8s Secret time.
#
# --- Everything below has a working default from application.yml; override only if needed. ---
ENV WORKER_HTTP_PORT="8081" \
    WORKER_ALLOW_INSECURE_GATEWAY="false" \
    WORKER_MAX_DIFF_BYTES="262144" \
    WORKER_MAX_RESPONSE_BYTES="200000" \
    LLAMA_URL="http://127.0.0.1:8000" \
    LLAMA_TEMPERATURE="0.1" \
    LLAMA_MAX_TOKENS="12000" \
    LLAMA_ALLOW_NON_LOOPBACK="false" \
    LLAMA_ENABLE_THINKING="true" \
    WORKER_POLL_INTERVAL_MS="3000" \
    WORKER_REQUEST_TIMEOUT_SEC="1800" \
    WORKER_GATEWAY_TIMEOUT_SEC="10" \
    WORKER_HEARTBEAT_INTERVAL_SEC="60"

# server.address is hardcoded to 127.0.0.1 in application.yml (WSR-12/FW-01: actuator must never
# be reachable off the Worker's own host) -- EXPOSE is documentation only, this port is never
# meant to be published with `-p` to anything but loopback.
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:${WORKER_HTTP_PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
