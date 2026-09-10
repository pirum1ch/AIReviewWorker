# LLM Worker

> **This repository is consumed as a git submodule of
> [`AIReviewGateway`](https://github.com/pirum1ch/AIReviewGateway) at `worker/`.** The Gateway repo is the
> platform docs home: `docs/worker-architecture.md`, `docs/worker-threat-model.md`,
> `docs/security/worker-sast-report.md`, and the root spec docs all live there, not here — see
> [AIReviewGateway/docs/worker-architecture.md](https://github.com/pirum1ch/AIReviewGateway/blob/master/docs/worker-architecture.md)
> and [AIReviewGateway/docs/worker-threat-model.md](https://github.com/pirum1ch/AIReviewGateway/blob/master/docs/worker-threat-model.md).
> Relative links below (`../README.md`, `../DEPLOYMENT.md`, …) resolve correctly in a
> `--recurse-submodules` checkout of the Gateway repo; if you're browsing this repo standalone on GitHub,
> use the Gateway repo's own tree instead.

The LLM Worker is a stateless transport agent that sits between the Review Gateway's job queue and one
local `llama-server` instance. This document is a deployment and integration guide, written the same way
as the Gateway's own `README.md`: everything described here reflects what is actually implemented
in this repo — `WorkerProperties`, `application.yml`, `WorkerLoop`/`HeartbeatScheduler`, the SAST report
under `docs/security/worker-sast-report.md`, and `docs/worker-architecture.md` (both in the Gateway repo).
Where the code leaves something unimplemented, or the original spec described something this codebase
does not do, this document says so explicitly instead of describing aspirational behavior.

## Table of contents

1. [Overview](#1-overview)
2. [How it works](#2-how-it-works)
3. [Requirements](#3-requirements)
4. [Build](#4-build)
5. [Configuration reference](#5-configuration-reference)
6. [Deployment](#6-deployment)
   - [6.4 Docker Compose](#64-docker-compose)
7. [Integration scheme](#7-integration-scheme)
8. [Observability](#8-observability)
9. [Operational notes](#9-operational-notes)
10. [Adding a prompt version](#10-adding-a-prompt-version)

---

## 1. Overview

The Worker is a maximally lightweight, **stateless** bridge co-located 1:1 with a `llama-server`
instance: it claims one job from the Gateway, calls its local `llama-server`, and submits the raw result
back. It owns **no** business logic, **no** persistent state, and **no** GitLab or PostgreSQL access —
all queue/retry/dedup/timeout/routing/publish logic lives in the Gateway (see the root README's
[§9 Worker protocol](../README.md#9-worker-protocol) for the Gateway-side view of this same contract).

- **1:1 pairing with one backend.** A Worker process is configured with exactly one `gateway.url` and
  one `llama.url`; it claims jobs only for the single `backend.id` it is configured with
  (`ClaimRequest.backendId`). Running N `llama-server` instances means running N Worker processes, each
  with its own `backend.id`/`llama.url`.
- **No GitLab or database access at all.** The Worker holds only a single bearer token
  (`gateway.api-key`) for the Gateway's Worker-facing endpoints; it has no GitLab API credentials, no
  JDBC driver, and no knowledge of the Gateway's schema, retry counting, deduplication, or how the raw
  LLM response gets parsed into structured comments.
- **Single-threaded job processing, plain threads.** The main loop (`core.WorkerLoop`) runs on exactly
  one `Thread` named `worker-loop` — there is no thread pool and no virtual-thread usage; capacity is
  structurally 1 concurrent job per Worker process. A second, per-job plain thread
  (`ScheduledExecutorService` named `worker-heartbeat`) exists only for the duration of one job.
- **No custom REST API of its own.** The only HTTP surface the Worker itself exposes is Spring Boot
  Actuator (health + Prometheus metrics, loopback-only — see [§8](#8-observability)); it never accepts
  business requests from anything.

## 2. How it works

### 2.0 Startup: Backend Self-Registration (optional)

Before the `worker-loop` thread is ever started, `lifecycle.WorkerRunner` (running synchronously on the
main thread, inside `SpringApplication.run()`, after context refresh but before Spring Boot publishes
`ReadinessState.ACCEPTING_TRAFFIC`) checks `backend.url` (`BACKEND_URL`, [§5](#5-configuration-reference)):

- **Unset (default):** one INFO log line ("Backend self-registration disabled (backend.url not set); this
  backend must be registered via the Gateway's admin API or SQL"), then straight to `workerLoop.start()`
  — today's behavior, unchanged.
- **Set:** `POST /backends/announce {backendId, workerId, url, model}` (`gateway.GatewayClient.announce`)
  is called once, before the loop starts, so an unregistered/unreachable backend reports readiness
  `OUT_OF_SERVICE` for free — no extra code needed for that signal. The outcome decides what happens next:
  - **Accepted (2xx):** INFO logging `name`/`status`/`created`; if the *effective* status the Gateway
    returns is not `ACTIVE` (e.g. an operator parked it `MAINTENANCE`/`OFFLINE`), an additional WARN that
    no jobs will be dispatched until an operator reactivates it. Either way, `workerLoop.start()` runs next.
  - **Gateway unreachable / `5xx` / `429` (`GatewayUnavailableException`):** WARN, then retry forever with
    the same capped-exponential backoff the claim loop uses (base `network.poll-interval-ms`, cap 60s,
    `core.CappedBackoff`) — the loop never starts until announce succeeds or the process is asked to stop.
    This retry sleeps in short slices and checks a shutdown signal (set from a `ContextClosedEvent`
    listener) between them, so a `SIGTERM` during this window still terminates promptly instead of
    requiring `SIGKILL` — a plain `Thread.sleep` loop here would not otherwise be interruptible by the
    JVM's normal shutdown hook, since the context has not finished starting yet. `503
    BACKEND_REGISTRY_FULL` (the Gateway's registry is at `BACKEND_MAX_BACKENDS`) lands here deliberately:
    it is a transient Gateway-side capacity condition, so this Worker comes up by itself the moment an
    operator decommissions a stale backend or raises the cap.
  - **`403`/`404` — non-fatal, WARN and continue:** this is the ordinary "this Gateway does not do
    self-registration" case (the Gateway's `gateway.backend.self-registration.enabled` is off, the token
    is not the `WORKER` token, or — for `404` — an older Gateway build predates the endpoint). One WARN
    naming the likely cause, then `workerLoop.start()` runs anyway. Deliberately **not** fatal: an operator
    flipping the Gateway's kill switch off must never crash-loop the whole Worker fleet.
  - **Every other `4xx` — fatal, startup fails.** The split is an explicit allowlist on both sides with
    **fail-fast as the default**: only `403`/`404` (above) and `429` (transient, retried) are exempt, and
    anything else in the `4xx` range — enumerated or not — is treated as a client-side condition that will
    never self-heal. `WorkerRunner` throws `IllegalStateException` naming the status and the likely cause;
    the loop never starts. The named causes:

    | Status | Likely cause named in the startup error |
    |---|---|
    | `400` | `backend.id`, `worker.id`, or `llama.model` failed the Gateway's validation — check `BACKEND_ID`/`WORKER_ID`/the model name for length or disallowed characters |
    | `401` | `GATEWAY_API_KEY` is missing, or is not the Gateway's `WORKER` token |
    | `409` | `backend.id` is already owned by a different `worker.id` on the Gateway — check for a copy-pasted `BACKEND_ID` across hosts |
    | `422` | `backend.url` was rejected by the Gateway's host allowlist, or was not a bare origin |
    | any other `4xx` | generic "the Gateway rejected this announce as a client-side error (`N`)" |

    Retrying any of these forever would produce a Worker that never starts, never exits, and logs a
    misleading "Gateway unavailable" WARN while the Gateway is perfectly healthy — which is why an
    unrecognised `4xx` fails fast rather than being assumed transient.

See the Gateway repo's `docs/backend-self-registration-architecture.md` §4 and
`docs/backend-self-registration-threat-model.md` (BSQ-18/19/20) for the full design and its rationale.

```
 worker-loop thread                                    worker-heartbeat thread (per job)
 ──────────────────                                    ─────────────────────────────────
      │
      ▼
 POST /jobs/claim ───────────────────────▶ Review Gateway
      │
      │ 204 No Content            ──▶ sleep(network.poll-interval-ms), loop
      │
      │ 200 OK {jobId, reviewId, payload:{diff, promptVersion, responseFormat, jsonSchema}}
      ▼
 resolve prompt:                            resolve decoder constraint (Structured Review Output, V5):
   classpath:prompts/<promptVersion>.yml       at most one of responseFormat/jsonSchema is non-null;
   literal {{DIFF}} substitution                Worker attaches it verbatim, never derives/edits it
      │                                                 │       │
      │ unknown promptVersion / oversized diff          │       │ both non-null / oversized / invalid JSON
      │  ──▶ AbandonJobException, jobs_failed++,        │       │  ──▶ AbandonJobException(CONSTRAINT_INVALID),
      │      POST /jobs/{id}/fail (best-effort), loop   │       │      jobs_failed++, POST /jobs/{id}/fail, loop
      ▼                                                 │       ▼
 start heartbeat scheduler ─────────────────────────────┤
      │                                                 ▼
      ▼                                       POST /jobs/{id}/heartbeat  (every heartbeat.interval-sec)
      │                                       "Job in progress" INFO once per tick
 "Starting inference" INFO                               │
      ▼                                                  │ shouldContinue:false / 403 / 404
 POST /v1/chat/completions  (async, cancellable,          │  ──▶ abort signal + cancel llama future
   response_format/json_schema attached verbatim         │
   when present) on llama-server                         │
      │                                                  ▼
      │ timeout / 5xx / malformed / oversize   job aborted: submit nothing, no completed/failed metric,
      │  ──▶ LlamaException, abandon, jobs_failed++,     NO /jobs/{id}/fail report (Gateway already owns it)
      │      POST /jobs/{id}/fail (best-effort)
      │ aborted mid-flight (see left) ──────────────────▶│
      │
      ▼ success
 stop heartbeat scheduler
      │
      ▼
 POST /jobs/{id}/result  (rawResponse + tokens + durationMs + model)
      │
      │ Gateway unreachable ──▶ retry with capped backoff, in memory only, until 200/403/404
      ▼
 200/403/404 ──▶ jobs_completed++, loop back to claim
```

Step by step, matching the code in `core.WorkerLoop`/`core.HeartbeatScheduler`:

1. **Claim.** `POST /jobs/claim` with `{backendId, workerId}` (`gateway.GatewayClient.claim`). `204` means
   nothing to claim right now (empty queue, backend not `ACTIVE`, or at capacity — all indistinguishable
   by design on the Gateway side); the loop sleeps `network.poll-interval-ms` and polls again. `200`
   yields `{jobId, reviewId, payload:{diff, promptVersion, chunkContext, systemMessages, responseFormat,
   jsonSchema}}` — the last two are Structured Review Output (V5), see step 2a below.
2. **Resolve the prompt** (`prompt.PromptTemplateService.resolve`): `promptVersion` is checked against an
   allowlist regex (`^[A-Za-z0-9._-]{1,64}$`, plus an explicit rejection of any value containing `..`)
   *before* it is ever used to build a resource path; the matching `classpath:prompts/<promptVersion>.yml`
   is loaded, and the diff is substituted into the template's `{{DIFF}}` placeholder with a single literal
   `String.replace` (never re-parsed by any template/expression engine). An unknown `promptVersion` or an
   oversized diff (`worker.limits.max-diff-bytes`) throws `AbandonJobException` — the job is abandoned
   before llama is ever called.
2a. **Resolve the decoder constraint** (`llama.DecoderConstraintResolver.resolve`, Structured Review
    Output, V5). `payload.responseFormat`/`payload.jsonSchema` are Gateway-computed, untrusted text; at
    most one may be non-null. The Worker's *only* business logic here is a handful of defensive bounds —
    it never builds, edits, or semantically inspects the constraint. Both non-null, the raw text exceeding
    `worker.limits.max-constraint-bytes` (measured on UTF-8 bytes, before any parsing), not valid JSON, or
    not parsing to a JSON object all throw `AbandonJobException` with reason `CONSTRAINT_INVALID` — see
    [§8.2](#82-log-patterns-worth-alerting-on). Both `null` (the common case today — `default-mode: OFF`)
    means no constraint; the Worker proceeds exactly as before this feature.
3. **Start the heartbeat.** `core.HeartbeatScheduler` starts a per-job, single-thread
   `ScheduledExecutorService` that calls `POST /jobs/{id}/heartbeat` every `heartbeat.interval-sec`
   (default 60s). `shouldContinue:false`, `403`, or `404` on that call sets an abort signal and cancels
   the in-flight llama call. A heartbeat tick that itself throws is caught (never lets the scheduler die
   silently) and, after 3 consecutive failures, also aborts the job as a fail-safe.
4. **Call llama-server.** `POST /v1/chat/completions` (OpenAI Chat-Completions shape) is issued
   asynchronously on the single shared `java.net.http.HttpClient`, so the abort signal above can cancel it
   mid-generation instead of waiting out the full `network.request-timeout-sec`. If step 2a resolved a
   non-null constraint, it is attached to this request **verbatim**, under the corresponding OpenAI-shaped
   wire field, never through the `{{DIFF}}`/`{{CHUNK_CONTEXT}}` template-substitution path. Immediately
   before this call, the Worker logs one INFO line (`"Starting inference (jobId=..., reviewId=...,
   diffChars=..., systemMessages=..., model=..., maxTokens=...)"` — sizes/counts only, never diff/prompt
   content) so there is no silent gap between "job claimed" and the first heartbeat tick. A timeout, a
   non-2xx status, a malformed/empty body, or a response exceeding `worker.limits.max-response-bytes` all
   result in the job being **abandoned** — no synthetic result is ever submitted for a llama failure.
5. **Submit the result.** `POST /jobs/{id}/result` with `{workerId, rawResponse, promptTokens,
   completionTokens, durationMs, model, finishReason}`. `finishReason` (Structured Review Output, V5) is
   `llama-server`'s own `choices[0].finish_reason` from the chat-completion response (e.g. `stop`,
   `length`), forwarded as-is — the Worker does not interpret it, the Gateway whitelist-parses it on
   receipt. If the Gateway is unreachable, the Worker retries this call with capped exponential backoff
   (holding the already-computed result in memory only, never on disk) until the Gateway responds
   `200`/`403`/`404` — this is transport-level redelivery of an idempotent, already-produced result, not a
   re-invocation of the LLM.
6. **On abandonment, report it (outbound call, `gateway.GatewayClient.reportFailure`).** If step 2, 2a, or
   step 4 abandons the job (`AbandonJobException`/`LlamaException`), the Worker sends a single best-effort
   `POST /jobs/{id}/fail` **synchronously**, after `HeartbeatScheduler.stop()` has already run and before
   the loop returns to step 1 — `{workerId, reason, detail}`, where `reason` is one of
   `LLM_EMPTY_RESPONSE`/`LLM_ERROR`/`LLM_TIMEOUT`/`LLM_RESPONSE_TOO_LARGE`/`PROMPT_INVALID`/`WORKER_ERROR`/
   `CONSTRAINT_INVALID` (the last one from step 2a, Structured Review Output)
   (classified from the exception, `error.JobFailureReason`) and `detail` is a **fixed, Worker-side
   constant per reason** (never `e.getMessage()`/`e.getCause().getMessage()`/`e.toString()` — a wrapped
   Jackson parse failure can quote the offending llama response verbatim, which must never leave this
   process). No retry/backoff of its own: any failure (Gateway unreachable, non-2xx, a `404` from an older
   Gateway build with no such endpoint) is logged at `WARN`, counted in `worker.gateway.errors`, and
   swallowed. Not sent at all when the job was aborted via `shouldContinue:false`/`403`/`404`
   (step 3) or via an interrupted result redelivery (step 5) or graceful-shutdown force-abandonment — in
   all three of those cases the Gateway already owns the transition, or a result may already be in flight,
   so a failure report would be redundant or contradictory.
7. **Loop.** Back to step 1, whether the previous job completed, was abandoned, or was aborted.

**The Gateway's stale-heartbeat sweep remains the correctness backstop**, regardless of whether step 6's
report is ever delivered — nothing in the system depends on it. If the Worker stops heartbeating entirely
(crash, network partition, before it can even attempt step 6), the sweep (default every 30s, `~180s`
staleness threshold per the root README's [§4.2](../README.md#42-everything-else-has-a-working-default))
still notices the stale heartbeat and requeues/fails the job on its own. Step 6 exists purely to collapse
that ~180-210s passive window down to well under a second for the common case of a Worker that is still
alive enough to report.

## 3. Requirements

| Component | Version / need | Notes |
|---|---|---|
| Java | 21 | `pom.xml` targets Java 21, Spring Boot 3.5.16 parent (pinned to the same line as the Gateway). |
| Maven | 3.9+ | Standard build; `spring-boot-maven-plugin` produces an executable fat JAR. |
| Network reachability to the Gateway | HTTPS (or loopback HTTP, dev-only) | See `gateway.url` in [§5](#5-configuration-reference). |
| Network reachability to a `llama-server` | HTTP (loopback by default) | See `llama.url` in [§5](#5-configuration-reference). |
| PostgreSQL | **not required** | The Worker has no JDBC dependency and no database access of any kind. |
| Docker | **not required to build or test** | Tests use real-socket `okhttp3:mockwebserver` instances standing in for both the Gateway and `llama-server` — no Testcontainers, no external services. A `Dockerfile` is provided as an *optional* containerized deployment path, plus a `docker-compose.yml` — see [§6.3](#63-containerization) and [§6.4](#64-docker-compose). |

## 4. Build

```bash
export JAVA_HOME="$HOME/tools/jdk-21.0.11+10"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"

mvn -q verify
```

(Adjust the `JAVA_HOME`/Maven paths to wherever your JDK 21 / Maven 3.9+ are actually installed — the
paths above are this project's own local toolchain layout, not a hard requirement. If you're building
from inside a Gateway checkout's `worker/` submodule path, `mvn -q -f worker/pom.xml verify` from the
Gateway root works the same way.)

`mvn verify` compiles, runs the full test suite (no external services needed — see [§3](#3-requirements)),
and packages the executable jar via `spring-boot-maven-plugin`. The build artifact is:

```
target/llm-worker.jar
```

(`target/llm-worker.jar.original` is the pre-repackage jar the Boot plugin leaves behind; it is
not the artifact to deploy.) Run it directly:

```bash
java -jar target/llm-worker.jar
```

with the required environment variables from [§5.1](#51-required-no-default--startup-fails-without-them)
set.

## 5. Configuration reference

All configuration is bound by `config.WorkerProperties` from `src/main/resources/application.yml`, which
has **no common prefix** — `gateway`, `worker`, `backend`, `llama`, `network`, `heartbeat`, `prompt` are
all top-level keys. `WorkerProperties.validateOnStartup()` (a `@PostConstruct` hook) fails startup fast
on any violation below; every failure message names the property only, never its configured value.

### 5.1 Required (no default — startup fails without them)

| Env var | Property | Purpose | Validation |
|---|---|---|---|
| `GATEWAY_URL` | `gateway.url` | Review Gateway base URL. | Must be a valid URI; must use `https://` **unless** the host is loopback (`127.0.0.1`/`::1`/`localhost`) **and** `worker.allow-insecure-gateway=true` — a non-loopback host always requires `https://`, regardless of that flag. |
| `GATEWAY_API_KEY` | `gateway.api-key` | Bearer token sent as `Authorization: Bearer` on every Gateway call; must match the Gateway's own `WORKER_TOKEN`. | Must be non-blank (JSR-380 `@NotBlank`). **Note:** `docs/worker-architecture.md` §4.2 documents a "≥ 32 chars" guidance for this token (mirroring the Gateway's own token-entropy check), but the current code does not enforce a minimum length — only non-blank is checked. Operators should still issue a high-entropy value (e.g. `openssl rand -hex 32`) to match the Gateway's actual `WORKER_TOKEN` requirement, even though the Worker itself will start with a short one. |
| `WORKER_ID` | `worker.id` | Self-chosen identifier reused on every claim/heartbeat/result call for a job (`ClaimRequest.workerId`). | Must be non-blank. |
| `BACKEND_ID` | `backend.id` | The backend's registered **name** in the Gateway's `backends` table, sent as `ClaimRequest.backendId`. | Must be non-blank. |
| `LLAMA_MODEL` | `llama.model` | Model name sent to `llama-server` and reported back as `ResultRequest.model` (llama-server is not queried for this — it is Worker config, since the Gateway never supplies it). | Must be non-blank. |

### 5.2 Everything else (has a working default)

| Env var | Property | Default | Notes |
|---|---|---|---|
| `BACKEND_URL` | `backend.url` | unset (self-registration disabled) | Backend Self-Registration: **optional, no separate enable flag — its presence is the only toggle** ([§2.0](#20-startup-backend-self-registration-optional)). The externally reachable `scheme://host[:port]` the **Gateway** should health-probe this backend at — not the same setting as `LLAMA_URL` below (where this Worker process itself connects, loopback by default; confusing the two is the single most likely operator mistake here). When set, `WorkerProperties.validateBackendUrl()` fails startup fast unless it: parses as a URI; uses `http://`/`https://`; resolves to a **non**-loopback host (message explicitly distinguishes it from `llama.url`); and is a **bare origin** — no path, query, fragment, or userinfo (the Gateway enforces the same rule server-side, `422 BACKEND_URL_REJECTED`; failing fast here gives a precise reason instead). Logs a WARN (never fails) if byte-identical to `LLAMA_URL`. |
| `WORKER_ALLOW_INSECURE_GATEWAY` | `worker.allow-insecure-gateway` | `false` | Dev-only escape hatch — see the `GATEWAY_URL` row above; has no effect for a non-loopback `gateway.url`. |
| `WORKER_MAX_DIFF_BYTES` | `worker.limits.max-diff-bytes` | `262144` (256 KiB) | Hard cap on the claimed diff, in UTF-8 bytes; exceeding it abandons the job before any llama call. Defensive bound against a misbehaving/compromised Gateway, set generously above the Gateway's own diff-token budget. |
| `WORKER_MAX_RESPONSE_BYTES` | `worker.limits.max-response-bytes` | `200000` | Hard cap on the llama response body, enforced **mid-stream** (never buffers past the cap); exceeding it abandons the job. Matches the Gateway's own documented "normal" raw-response ceiling. |
| `WORKER_MAX_CONSTRAINT_BYTES` | `worker.limits.max-constraint-bytes` | `69632` (68 KiB) | Structured Review Output (V5). Hard cap, in UTF-8 bytes measured **before** any JSON parsing, on the Gateway-supplied `responseFormat`/`jsonSchema` decoder constraint; exceeding it abandons the job with reason `CONSTRAINT_INVALID`. Deliberately larger than the Gateway's own `gateway.structured.max-schema-bytes` (default `65536`) — the wire-level `response_format`/`json_schema` wrapper `llama-server` expects adds bytes on top of the raw schema, and setting the two equal would produce a fleet-wide `CONSTRAINT_INVALID` loop the first time a schema near the limit is claimed. If you change the Gateway's `max-schema-bytes`, recompute this value too (see the root `DEPLOYMENT.md` §8c's cross-module coupling note). |
| `LLAMA_URL` | `llama.url` | `http://127.0.0.1:8000` | Must be a valid `http`/`https` URI. A non-loopback host only logs a WARN at startup (does not fail) unless `llama.allow-non-loopback` is also set — the llama socket is unauthenticated by design. |
| `LLAMA_ALLOW_NON_LOOPBACK` | `llama.allow-non-loopback` | `false` | Suppresses the non-loopback `llama.url` startup warning; never fail-fast either way (this is a SHOULD, not a MUST). |
| `LLAMA_TEMPERATURE` | `llama.temperature` | `0.1` | Sampling temperature sent to llama-server (unless overridden by the prompt template — [§10](#10-adding-a-prompt-version)). |
| `LLAMA_MAX_TOKENS` | `llama.max-tokens` | `4096` | `max_tokens` sent to llama-server (unless overridden by the prompt template). Must be `> 0`. |
| `WORKER_POLL_INTERVAL_MS` | `network.poll-interval-ms` | `3000` | Sleep between `POST /jobs/claim` polls after a `204`. Must be `> 0`. |
| `WORKER_REQUEST_TIMEOUT_SEC` | `network.request-timeout-sec` | `1800` (30 min) | Bound on the whole llama chat-completion call (a single LLM generation can legitimately take tens of minutes). Must be `> 0`. |
| `WORKER_GATEWAY_TIMEOUT_SEC` | `network.gateway-timeout-sec` | `10` | Read timeout for every Gateway call (claim/heartbeat/result) — deliberately short; Gateway calls must never block as long as an LLM completion. Must be `> 0`. |
| `WORKER_HEARTBEAT_INTERVAL_SEC` | `heartbeat.interval-sec` | `60` | Cadence of `POST /jobs/{id}/heartbeat` while a job is running. **Hard-rejected at startup if `>= 180`** (the Gateway's stale-heartbeat threshold — a misconfiguration here could not otherwise cause every job to self-evict); **logs a WARN if `> 90`** (little margin left). Must also be `> 0`. |
| `WORKER_HTTP_PORT` | `server.port` | `8081` | Port for the embedded server, which hosts **only** Actuator (see [§8](#8-observability)) — the Worker has no business REST endpoints. |
| `WORKER_IDLE_SUMMARY_INTERVAL_SEC` | `worker.log.idle-summary-interval-sec` | `300` | Worker Observability & Claim Latency. At most one INFO idle-liveness summary line (`"Idle: no job available in the last N poll(s) (backend=...)"`) per this many seconds while no job is available; the poll counter resets on a successful claim. `0` disables it entirely. Must be `>= 0`. |

### 5.3 Hardcoded in `application.yml` (no environment-variable placeholder)

These are not `${VAR}`-templated in the shipped `application.yml`; changing them means editing/rebuilding
or supplying an external Spring property source (e.g. a mounted `application.yml`, `-D` system
properties, or `SPRING_APPLICATION_JSON`), not just setting an environment variable:

| Property | Value | Notes |
|---|---|---|
| `worker.version` | `1.0.0` | Bound and validated, but **not currently read anywhere else in the code** (no log line, metric, or Gateway call uses it) — informational/reserved only. |
| `prompt.location` | `classpath:prompts/` | **Must start with `classpath:`** — startup fails otherwise. Templates ship only inside the fat JAR; there is no supported way to point this at an external/operator-writable directory (see [§10](#10-adding-a-prompt-version)). |
| `server.address` | `127.0.0.1` | The whole embedded server (and therefore Actuator, since no distinct `management.server.port` is configured) binds to loopback only. `WorkerProperties.validateServerBinding()` fails startup fast if the address that is *actually* effective for Actuator resolves to anything but loopback — see the inline comment in `application.yml` for why `server.address` (not `management.server.address`) is the property that matters here. |
| `management.endpoints.web.exposure.include` | `health,prometheus` | No other Actuator endpoint (`env`, `heapdump`, `beans`, etc.) is exposed. |
| `management.endpoint.health.probes.enabled` | `true` | Exposes `/actuator/health/liveness` and `/actuator/health/readiness` groups in addition to `/actuator/health`. |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | The graceful-shutdown grace period — see [§9](#9-operational-notes). |

### 5.4 Example minimal launch

```bash
export GATEWAY_URL="https://review-gateway.internal"
export GATEWAY_API_KEY="<the Gateway's WORKER_TOKEN value>"
export WORKER_ID="worker-mac-mini-01"
export BACKEND_ID="mac-mini-01"
export LLAMA_MODEL="qwen2.5-coder"
# LLAMA_URL defaults to http://127.0.0.1:8000; override only if llama-server listens elsewhere.

java -jar target/llm-worker.jar
```

## 6. Deployment

No launchd `.plist`/systemd unit ships in this repository. A `Dockerfile` **does** ship (see
[§6.3](#63-containerization)) as an optional containerized path; the systemd/launchd examples below are
still the primary target the original spec doc (`LLM Worker (Executor)_ prompt.md`) calls for (launchd on
a Mac mini), and remain illustrative configuration for an operator to adapt, not files present in this
repository.

### 6.1 systemd (Linux)

```ini
# /etc/systemd/system/llm-worker.service
[Unit]
Description=LLM Worker (Review Gateway)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=llm-worker
Group=llm-worker
EnvironmentFile=/etc/llm-worker/llm-worker.env
ExecStart=/usr/bin/java -XX:-HeapDumpOnOutOfMemoryError -jar /opt/llm-worker/llm-worker.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`/etc/llm-worker/llm-worker.env` holds the variables from [§5.4](#54-example-minimal-launch) (file
permissions restricted to the `llm-worker` user, since `GATEWAY_API_KEY` is a bearer secret).
`-XX:-HeapDumpOnOutOfMemoryError` is the JVM flag `application.yml`'s own comment recommends (an OOM heap
dump could otherwise persist an in-flight diff/LLM response to disk in plaintext) — the Worker only
*checks and warns* if this flag is missing at startup; it cannot enforce it from inside the JVM.

### 6.2 launchd (macOS / Mac mini)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.review.llm-worker</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-XX:-HeapDumpOnOutOfMemoryError</string>
        <string>-jar</string>
        <string>/opt/llm-worker/llm-worker.jar</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>GATEWAY_URL</key>
        <string>https://review-gateway.internal</string>
        <key>GATEWAY_API_KEY</key>
        <string>REPLACE_ME</string>
        <key>WORKER_ID</key>
        <string>worker-mac-mini-01</string>
        <key>BACKEND_ID</key>
        <string>mac-mini-01</string>
        <key>LLAMA_MODEL</key>
        <string>qwen2.5-coder</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/var/log/llm-worker/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>/var/log/llm-worker/stderr.log</string>
</dict>
</plist>
```

Load it with `launchctl load /Library/LaunchDaemons/com.review.llm-worker.plist`. As with the systemd
unit, run this under a dedicated non-root user and restrict the plist's file permissions, since it embeds
`GATEWAY_API_KEY` in plaintext.

### 6.3 Containerization

`Dockerfile` is a multi-stage build: `maven:3.9-eclipse-temurin-21` compiles the fat jar
(`RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package`), and the runtime stage is
`eclipse-temurin:21-jre-jammy` running as a non-root `worker` user, with `curl` installed only so the
image's own `HEALTHCHECK` (`GET http://127.0.0.1:${WORKER_HTTP_PORT}/actuator/health` every 15s) can
probe itself. No image is published anywhere — build it locally:

```bash
docker build -t llm-worker:latest .
```

Run it with the five required variables from [§5.1](#51-required-no-default--startup-fails-without-them)
(`GATEWAY_URL`, `GATEWAY_API_KEY`, `WORKER_ID`, `BACKEND_ID`, `LLAMA_MODEL`) plus whatever [§5.2](#52-everything-else-has-a-working-default)
overrides you need — every one of those is already declared as an `ENV` in the image with the same
default `application.yml` ships, so you only need `-e` for what you're actually changing:

```bash
docker run -d --name llm-worker \
  -e GATEWAY_URL="https://review-gateway.internal" \
  -e GATEWAY_API_KEY="<the Gateway's WORKER_TOKEN value>" \
  -e WORKER_ID="worker-mac-mini-01" \
  -e BACKEND_ID="mac-mini-01" \
  -e LLAMA_MODEL="qwen2.5-coder" \
  -e LLAMA_URL="http://192.168.1.50:8000" \
  llm-worker:latest
```

**The `WORKER_HTTP_PORT`/Actuator port (default `8081`) is deliberately not meant to be published with
`-p`.** `server.address: 127.0.0.1` is hardcoded (WSR-12/FW-01, [§5.3](#53-hardcoded-in-applicationyml-no-environment-variable-placeholder))
and `WorkerProperties.validateServerBinding()` fails startup if that ever resolves to anything else — the
container's own `HEALTHCHECK` runs `curl` *inside* the container's network namespace, so it can reach
loopback without any port mapping; checking it from the host means `docker exec`, not `curl localhost:8081`:

```bash
docker exec llm-worker curl -s http://127.0.0.1:8081/actuator/health
docker exec llm-worker curl -s http://127.0.0.1:8081/actuator/prometheus | grep '^worker_'
docker inspect --format '{{.State.Health.Status}}' llm-worker   # starting / healthy / unhealthy
```

**`GATEWAY_URL` over HTTPS to a remote Gateway is the default and recommended shape for this Worker**,
not a special case. Real deployments run a Worker on its own host (e.g. a Mac mini next to its
`llama-server`), reaching a Gateway that lives elsewhere, over the network — see
[§6.4](#64-docker-compose) and [root DEPLOYMENT.md §2](../DEPLOYMENT.md#2-prerequisites). `gateway.url`
must be `https://` unless the host is loopback **and** `WORKER_ALLOW_INSECURE_GATEWAY=true`
([§5.1](#51-required-no-default--startup-fails-without-them)) — a container-to-container hostname on a
Docker bridge network (e.g. `http://review-gateway:8080`) is **not** loopback, so that escape hatch does
not apply there. Forwarding a loopback `GATEWAY_URL` to a remote Gateway through a **non-encrypting**
relay (`socat`, a plain `proxy_pass`, a published-port shim) is **forbidden** even with the flag set — it
puts `GATEWAY_API_KEY` and every diff on the wire in cleartext while the loopback check sees nothing
wrong. An **encrypting** tunnel (`ssh -L`, WireGuard, an mTLS mesh sidecar) terminated locally, so the
Worker genuinely only ever sees loopback, is the acceptable alternative. The only legitimate use of the
flag is a same-host dev/smoke-test setup where the Gateway process itself listens on that host's loopback
address — for example running both containers with `--network host` and pointing this Worker at the
Gateway's own published loopback port (`GATEWAY_URL=http://127.0.0.1:8080`,
`WORKER_ALLOW_INSECURE_GATEWAY=true`); see [root DEPLOYMENT.md](../DEPLOYMENT.md) for a full worked
example of this local smoke-test topology, which was used to verify this image end-to-end against a real
containerized Gateway.

**If the Gateway's reverse proxy presents a private/self-signed certificate**, prefer a publicly-trusted
or dedicated-internal-CA certificate for it. **Do not reuse the Gateway's `gitlab.local` mkcert CA for
the reverse proxy, and do not distribute an mkcert CA to the Worker fleet** — an mkcert CA's private key
lives on a developer workstation and can mint a valid certificate for any hostname; trusting it
fleet-wide turns one workstation compromise into a MITM of every Worker→Gateway hop. If a custom
truststore is genuinely unavoidable, it must be a copy of the JDK `cacerts` with the internal CA
**imported** (extend, never replace, the JDK defaults), bind-mounted `:ro` (see [§6.4](#64-docker-compose)),
and never baked into the image. Disabling certificate/hostname verification, a trust-all `SSLContext`, or
any `-Dcom.sun.net.ssl…` workaround is forbidden — the Worker's `java.net.http.HttpClient` uses the JVM's
default trust behavior and there is no supported way to weaken it.

### 6.4 Docker Compose

`docker-compose.yml` at the repo root runs **one** Worker service against a **remote** Gateway and a
**remote** `llama-server` — neither is part of this file (that's the point of this Worker repo being
split out of `AIReviewGateway`). Required `.env` keys mirror [§5.1](#51-required-no-default--startup-fails-without-them):
`GATEWAY_URL`, `GATEWAY_API_KEY`, `WORKER_ID`, `BACKEND_ID`, `LLAMA_URL`, `LLAMA_MODEL` (see
`.env.example` for the full list including optional tuning knobs). No `ports:` — the Actuator stays
loopback-only by design ([§8](#8-observability)):

```bash
cp .env.example .env   # fill in real values
docker compose up --build

docker compose exec worker curl -s http://127.0.0.1:8081/actuator/health
```

One host pairs 1:1 with one `llama-server`; a host running a second `llama-server` copies the `worker:`
service block again rather than adding a scaling knob (§1's 1:1 pairing). The dev-loopback affordance
(`network_mode: "host"` + `WORKER_ALLOW_INSECURE_GATEWAY=true`, Linux hosts only) ships commented out in
the file, with the same forbidden-pattern warning as above.

## 7. Integration scheme

Slotting a Worker into an existing Review Gateway deployment:

1. **Register a backend in the Gateway.** There is no REST endpoint for this (per the root README's
   [§5 Deployment](../README.md#5-deployment)) — insert a row directly into the Gateway's `backends`
   table:

   ```sql
   INSERT INTO backends (name, url, model, capacity)
   VALUES ('mac-mini-01', 'http://192.168.1.50:8080', 'qwen2.5-coder', 1);
   ```

   The `name` here (`mac-mini-01`) is exactly the value this Worker must be configured with as
   `BACKEND_ID`/`backend.id` — the field is misleadingly called `backendId` in the wire protocol, but it
   carries the backend's **name**, not its numeric database id. Confirm registration with
   `GET /backends` (ADMIN token) on the Gateway.
2. **Issue/confirm the Worker bearer token** on the Gateway side — it is the Gateway's own
   `WORKER_TOKEN` environment variable (see the root README's
   [§4.1](../README.md#41-required-secrets-no-default--startup-fails-without-them)); the same value goes
   into this Worker's `GATEWAY_API_KEY`.
3. **Point the Worker at the Gateway and at its own `llama-server`** via `GATEWAY_URL` and `LLAMA_URL`
   ([§5](#5-configuration-reference)).
4. **Scale by adding more Worker processes, one per `llama-server` host.** Each additional backend gets
   its own `backends` row (a distinct `name`) and its own Worker process configured with that
   `BACKEND_ID` and that host's `LLAMA_URL` — there is no multi-backend or multi-model support within a
   single Worker process (§1's 1:1 pairing).

### 7.1 Troubleshooting curl examples

The four Gateway endpoints this Worker calls, useful for testing a Gateway/Worker pairing by hand
(`$WORKER_TOKEN` below is the Gateway's configured token, i.e. this Worker's `GATEWAY_API_KEY`):

```bash
# 1. Claim
curl -s -X POST "$GATEWAY_URL/jobs/claim" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "backendId": "mac-mini-01", "workerId": "worker-mac-mini-01" }'
# 200 -> {"jobId":456,"reviewId":123,"payload":{"diff":"...","promptVersion":"v1"}}
# 204 -> nothing to claim (empty body)

# 2. Heartbeat (use the jobId and the same workerId from the claim above)
curl -s -X POST "$GATEWAY_URL/jobs/456/heartbeat" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "workerId": "worker-mac-mini-01" }'
# 200 -> {"shouldContinue":true}   (false means: stop generating, abandon the job)
# 404/403 -> unknown job / not this job's owner

# 3. Result
curl -s -X POST "$GATEWAY_URL/jobs/456/result" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "workerId": "worker-mac-mini-01",
        "rawResponse": "[{\"file\":\"Foo.java\",\"line\":42,\"severity\":\"major\",\"comment\":\"...\"}]",
        "promptTokens": 3200,
        "completionTokens": 180,
        "durationMs": 45000,
        "model": "qwen2.5-coder"
      }'
# 200 -> {"reviewId":123,"status":"COMPLETED"}  (idempotent: safe to resend the exact same body)

# 4. Report a failure (Worker Observability & Claim Latency) -- sent instead of step 3 when the job is
# abandoned; best-effort, no retry.
curl -s -X POST "$GATEWAY_URL/jobs/456/fail" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "workerId": "worker-mac-mini-01", "reason": "LLM_TIMEOUT", "detail": "llama-server did not respond within the configured timeout" }'
# 200 -> {"accepted":true}  (identical whether applied or an idempotent no-op)
# 404/403 -> unknown job / not this job's owner (same opaque semantics as heartbeat/result)
```

## 8. Observability

Spring Boot Actuator is exposed on the loopback-bound embedded server only
(`server.address: 127.0.0.1` — see [§5.3](#53-hardcoded-in-applicationyml-no-environment-variable-placeholder)),
at the default `/actuator` base path:

- `GET /actuator/health` (and, since health probes are enabled, `/actuator/health/liveness` /
  `/actuator/health/readiness`).
- `GET /actuator/prometheus` — the `worker_*` metrics below, in Prometheus exposition format.

### 8.1 Metrics (`metrics.WorkerMetrics`)

| Prometheus name | Micrometer type | Meaning |
|---|---|---|
| `worker_jobs_total` | Counter | Incremented once per job successfully claimed (`POST /jobs/claim` returned `200`), before any inference is attempted. |
| `worker_jobs_completed_total` | Counter | Incremented only once the Gateway has actually acknowledged the result (`POST /jobs/{id}/result` returned `200`/`403`/`404` — all three are terminal, idempotent-acknowledged outcomes from the Worker's perspective). An interrupted/abandoned result redelivery (e.g. shutdown grace period elapsing mid-retry) does **not** increment this. |
| `worker_jobs_failed_total` | Counter | Incremented when a job is abandoned: unknown `promptVersion`, oversized diff, any llama failure (timeout/5xx/malformed/oversize), or a result redelivery that was interrupted/abandoned before the Gateway ever acknowledged it. |
| `worker_llama_duration_seconds_count` / `_sum` | Timer | Latency of successful llama-server chat-completion calls only (a call that fails before a result is parsed is not recorded here). |
| `worker_gateway_errors_total` | Counter | Incremented every time a Gateway call (claim, result-submission during redelivery, or a `POST /jobs/{id}/fail` report) fails with a connection error or `5xx`/other non-2xx, i.e. every `GatewayUnavailableException`. Heartbeat failures are **not** counted here (see the log-based signal below instead). |
| `worker_failures_reported_total` | Counter | Worker Observability & Claim Latency. Incremented once per `POST /jobs/{id}/fail` attempt the Worker made (whether or not the Gateway ultimately accepted it — see `worker_gateway_errors_total` for delivery failures). Distinguishes "failed and told the Gateway" from "failed silently" in `worker_jobs_failed_total`. |
| `worker_uptime_seconds` | Gauge | Seconds since the `WorkerMetrics` bean was constructed (process start, effectively). |

A job that is aborted mid-flight because the Gateway said `shouldContinue:false`/`403`/`404` on a
heartbeat increments **neither** `worker_jobs_completed_total` nor `worker_jobs_failed_total` — it is
neither a Worker-side success nor a Worker-side failure; the outcome is tracked entirely by the Gateway
(the Review already moved to a state where this job's result would be discarded anyway).

### 8.2 Log patterns worth alerting on

The Worker logs to stdout only (`logback-spring.xml`, no file appender — nothing this process logs is
meant to persist on disk), and never logs the bearer token, the diff, or the raw LLM response content —
only ids, statuses, and sizes. Notable lines (all from `core.WorkerLoop`/`core.HeartbeatScheduler` unless
noted):

| Log line (abbreviated) | Level | Meaning |
|---|---|---|
| `Claimed job (jobId=…, reviewId=…)` | INFO | A job was actually claimed. **Worker Observability & Claim Latency:** the empty-poll (`204`) case now drops to DEBUG, so this line only fires when there is real work — a busy Worker used to be silent while an idle one logged every poll; that is now inverted. |
| `Job in progress (jobId=…, workerId=…, elapsedSec=…, heartbeats=…)` | INFO | One line per heartbeat tick (`heartbeat.interval-sec`, default once a minute) while a job is genuinely running — closes what was previously up to `network.request-timeout-sec` (1800s) of silence for a busy Worker. |
| `Starting inference (jobId=…, reviewId=…, diffChars=…, systemMessages=…, model=…, maxTokens=…)` | INFO | Immediately before the llama-server call — sizes/counts only, never diff/prompt/response content. |
| `Idle: no job available in the last {N} poll(s) (backend=…)` | INFO | Rate-limited idle-liveness summary (`worker.log.idle-summary-interval-sec`, default once per 5 minutes) — proves a genuinely idle Worker is still alive without spamming one line per poll. |
| `Gateway unavailable while claiming a job; backing off {N} ms` | WARN | Gateway is unreachable/erroring on claim; the loop is backing off (capped at 60s) and will keep retrying — not itself an outage requiring restart, but worth alerting if sustained. |
| `Gateway unavailable while submitting result; retrying in {N} ms` | WARN | Same, but for an already-computed result stuck in redelivery — a sustained run of these means completed work is not reaching the Gateway. |
| `Job abandoned (jobId=…, reason=…, exceptionType=…)` | WARN | A job was abandoned (prompt/llama/constraint failure), classified into one of `LLM_EMPTY_RESPONSE`/`LLM_ERROR`/`LLM_TIMEOUT`/`LLM_RESPONSE_TOO_LARGE`/`PROMPT_INVALID`/`WORKER_ERROR`/`CONSTRAINT_INVALID`. **Worker Observability & Claim Latency:** never logs the exception's message any more (only its class name) — a wrapped Jackson parse failure can quote the offending llama response verbatim, which must never reach a log line. Expected occasionally; worth alerting on a sustained rate (points at a broken `llama-server` or a bad prompt template). `reason=CONSTRAINT_INVALID` specifically (Structured Review Output, V5) means the Gateway-supplied decoder constraint failed this Worker's own defensive re-check (both `responseFormat`/`jsonSchema` set, oversized, not valid JSON, or not a JSON object) — a sustained rate of these points at a `gateway.structured.max-schema-bytes`/`worker.limits.max-constraint-bytes` mismatch (see `DEPLOYMENT.md` §8c) rather than a broken `llama-server`. |
| `Failed to report job failure to the Gateway; the stale-heartbeat sweep will recover it (jobId=…)` | WARN | The best-effort `POST /jobs/{id}/fail` report failed (Gateway unreachable, non-2xx, or an older Gateway build without the endpoint) — swallowed, no retry; the passive sweep remains the backstop. |
| `Result redelivery abandoned before the Gateway ever acknowledged it; counting job as failed` | WARN | A result redelivery was interrupted (typically the shutdown grace period elapsing) before the Gateway confirmed receipt. |
| `Heartbeat tick failed ({N} consecutive) (jobId=…)` | WARN | A single heartbeat attempt failed (e.g. transient Gateway error); the scheduler keeps running. |
| `Heartbeat failed {N} times in a row (jobId=…); aborting fail-safe rather than running blind` | ERROR | Fail-safe abort after 3 consecutive heartbeat failures (`WSR-15`) — the Gateway may believe this job is still running while the Worker has actually given up on it; the Gateway's own heartbeat-timeout sweep will eventually reclaim it. |
| `llama.url host is not loopback … confirm this is intentional` | WARN (startup only) | Non-default, non-loopback `llama.url` without `LLAMA_ALLOW_NON_LOOPBACK` — confirm this is intentional for the deployment. |
| `JVM flag -XX:+HeapDumpOnOutOfMemoryError is enabled` | WARN (startup only) | The recommended `-XX:-HeapDumpOnOutOfMemoryError` launch flag is missing — see [§6](#6-deployment). |

## 9. Operational notes

- **Graceful shutdown.** `lifecycle.GracefulShutdown` (`SmartLifecycle`) stops the loop from claiming any
  *new* job immediately, then waits up to `spring.lifecycle.timeout-per-shutdown-phase` (default `30s`)
  for the current job to finish naturally (llama completes, result gets submitted). If that window
  elapses with a job still running, the job is **force-abandoned**: the in-flight llama call is
  cancelled, and the loop thread is interrupted (which also stops a result-redelivery retry loop that was
  stuck backing off against an unreachable Gateway). Either way, process shutdown itself is bounded — it
  never blocks indefinitely on an LLM generation, which can legitimately run tens of minutes.
- **Gateway outage.** Claim and result-submission calls both retry with capped exponential backoff
  (starting at `network.poll-interval-ms`, doubling, capped at 60s) on connection failure/`5xx`,
  incrementing `worker_gateway_errors_total` each time. The process **never exits** on a Gateway outage —
  it keeps retrying indefinitely.
- **llama-server failure** (timeout, 5xx, malformed body, connection refused, oversized response). The
  job is abandoned — no result is ever submitted for it. The Worker itself does not report this failure
  to the Gateway (there is no `/failed` endpoint to call); the Gateway's own stale-heartbeat sweep
  reclaims the stuck `RUNNING` job once its heartbeat goes stale (see the root README's heartbeat-timeout
  default, `~180s`) and requeues or fails it according to the Gateway's own retry policy.
- **Cancelled/superseded review.** The next heartbeat after an admin cancel (`DELETE /reviews/{id}`) or a
  new push superseding the review (`OBSOLETE`) gets `shouldContinue:false` from the Gateway; the Worker
  aborts the in-flight llama call immediately (rather than waiting for it to finish) and moves on to the
  next claim — no result is submitted, and neither the completed nor the failed metric is incremented for
  that job.

## 10. Adding a prompt version

Templates are resolved **only** from the classpath (`prompt.PromptTemplateService`, backed by
`prompt.location: classpath:prompts/`) — there is no supported way to load one from an external,
operator-writable directory at runtime. To add a new `promptVersion`:

1. Add a file at `src/main/resources/prompts/<name>.yml` and rebuild the fat JAR — the file must
   be baked into the jar at build time.
2. `<name>` must match the allowlist regex `^[A-Za-z0-9._-]{1,64}$` and must not contain the literal
   substring `..` (checked *before* the file is resolved — this is what a Gateway-supplied `promptVersion`
   is validated against on every claimed job; an unmatched value abandons the job rather than falling back
   to any default template).
3. Template format (see the shipped `prompts/v1.yml` for a complete example):

   ```yaml
   system: >
     Optional system-role instructions for the model.
   user: |
     Required user-role instructions. Must contain the literal placeholder {{DIFF}} exactly once,
     which is substituted with the diff text via a single literal String.replace — never re-parsed
     as a template or expression, so it is safe for a diff to contain arbitrary text (including
     things that look like template syntax).
   # Optional overrides; if omitted, the Worker falls back to llama.model/llama.temperature/llama.max-tokens.
   model: some-model-name
   temperature: 0.2
   maxTokens: 2048
   ```

   `user` is required (a template missing it, or that isn't a YAML mapping at all, abandons the job);
   `system` and the three overrides are all optional.
4. The `user` text must instruct the model to emit the exact shape the Gateway's own parser expects,
   since the Worker forwards the raw model output to the Gateway verbatim, with no parsing or validation
   of its own — but **which mechanism actually enforces that shape now depends on the template**:
   - For a `v1`/`v2`-style template, prose instruction is the **only** mechanism — a JSON array of
     `{file, line, severity, comment}` objects (see the root README's
     [§6.6](../README.md#66-post-jobsidresult--submit-the-result-worker)). The shipped `prompts/v1.yml`
     does exactly this and is the reference example to copy from.
   - For a **Structured Review Output (V5)** template (`promptVersion` in
     `gateway.structured` — see the shipped `prompts/v3.yml`), prose instruction alone is no longer the
     only mechanism: on a backend whose `structured_output_mode` is not `OFF`, the Gateway additionally
     attaches a decoder-level JSON Schema constraint (`payload.responseFormat`/`payload.jsonSchema`,
     [§2](#2-how-it-works) step 2a) that this Worker forwards verbatim — the *shape* guarantee comes from
     that constraint (when active) plus the Gateway's own strict re-validation on receipt, not from the
     template text alone. The template's `user` text should still describe the desired shape in prose
     (so the response is still well-formed on a backend running with the constraint `OFF`, which is the
     shipped default), but it is a second, complementary mechanism now, not the only one. See the root
     README's [§6.1c](../README.md#61c-structured-review-output-and-response-validation) for the full
     model, and `src/main/resources/prompts/v3.yml` for the shipped example.
