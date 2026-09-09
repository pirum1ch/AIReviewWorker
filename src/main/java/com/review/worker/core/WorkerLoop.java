package com.review.worker.core;

import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.error.GatewayUnavailableException;
import com.review.worker.error.JobFailureReason;
import com.review.worker.error.LlamaException;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.ResultOutcome;
import com.review.worker.gateway.dto.ClaimResponse;
import com.review.worker.gateway.dto.FailRequest;
import com.review.worker.gateway.dto.ResultRequest;
import com.review.worker.llama.DecoderConstraint;
import com.review.worker.llama.DecoderConstraintResolver;
import com.review.worker.llama.LlamaClient;
import com.review.worker.metrics.WorkerMetrics;
import com.review.worker.prompt.PromptTemplateService;
import com.review.worker.prompt.ResolvedPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single job-loop thread (architecture §5/§6): claim → resolve prompt → start heartbeat → call
 * llama-server (async, cancellable) → submit result → loop. Capacity is structurally 1 (a single plain
 * thread, never a pool) matching the backend's {@code parallel:1} assumption (architecture §2).
 *
 * <p>This class owns no Spring-managed lifecycle by itself — {@code lifecycle.WorkerRunner} starts it and
 * {@code lifecycle.GracefulShutdown} stops it — so it can be constructed and driven directly in tests
 * without booting a Spring context.
 */
@Component
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final String THREAD_NAME = "worker-loop";

    /**
     * WOR-05: fixed, Worker-side-constant vocabulary for the {@code POST /jobs/{id}/fail} {@code detail}
     * field, one entry per {@link JobFailureReason} — never an exception message, never LLM/prompt/diff
     * content. A {@code Jackson} parse-failure message can quote the offending LLM-generated JSON
     * verbatim, which must never reach the Gateway's audit trail (WOT-03).
     */
    private static final Map<JobFailureReason, String> DETAIL_BY_REASON = buildDetailByReason();

    private static Map<JobFailureReason, String> buildDetailByReason() {
        Map<JobFailureReason, String> map = new EnumMap<>(JobFailureReason.class);
        map.put(JobFailureReason.LLM_EMPTY_RESPONSE, "llama-server response had no choices or empty message content");
        map.put(JobFailureReason.LLM_ERROR, "llama-server call failed");
        map.put(JobFailureReason.LLM_TIMEOUT, "llama-server did not respond within the configured timeout");
        map.put(JobFailureReason.LLM_RESPONSE_TOO_LARGE, "llama-server response exceeded the configured size limit");
        map.put(JobFailureReason.PROMPT_INVALID, "prompt resolution failed (invalid promptVersion, oversized "
                + "diff, or missing template)");
        map.put(JobFailureReason.WORKER_ERROR, "unclassified worker-side failure");
        map.put(JobFailureReason.CONSTRAINT_INVALID, "Gateway-supplied decoder constraint failed the "
                + "Worker's defensive re-check (both fields set, oversized, invalid JSON, or not an object)");
        // SGB-06/SOGB-07 (Structured Output Grammar Budget): a fixed, Worker-side-constant sentence --
        // never the backend's actual error body text, which is scanned for a token match and then
        // discarded (LlamaClient never returns/logs/stores it).
        map.put(JobFailureReason.CONSTRAINT_REJECTED, "llama-server refused the decoder-constraint grammar "
                + "(compile-time rejection)");
        return map;
    }

    private final GatewayClient gatewayClient;
    private final LlamaClient llamaClient;
    private final PromptTemplateService promptTemplateService;
    private final DecoderConstraintResolver decoderConstraintResolver;
    private final HeartbeatScheduler heartbeatScheduler;
    private final WorkerMetrics metrics;
    private final WorkerProperties properties;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile AbortSignal currentAbortSignal;

    /**
     * WOC-04: single-threaded loop (this field is touched only from the {@code worker-loop} thread), so
     * plain fields suffice -- no synchronization, no new scheduler. {@code idlePollCount} resets to 0 on
     * every successful claim; {@code lastIdleSummaryAtMillis} gates the log line to at most once per
     * {@code worker.log.idle-summary-interval-sec}.
     */
    private long idlePollCount;
    private long lastIdleSummaryAtMillis;

    public WorkerLoop(GatewayClient gatewayClient,
                       LlamaClient llamaClient,
                       PromptTemplateService promptTemplateService,
                       DecoderConstraintResolver decoderConstraintResolver,
                       HeartbeatScheduler heartbeatScheduler,
                       WorkerMetrics metrics,
                       WorkerProperties properties) {
        this.gatewayClient = gatewayClient;
        this.llamaClient = llamaClient;
        this.promptTemplateService = promptTemplateService;
        this.decoderConstraintResolver = decoderConstraintResolver;
        this.heartbeatScheduler = heartbeatScheduler;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** Starts the {@code worker-loop} thread. A no-op if already started. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        shuttingDown.set(false);
        running.set(true);
        thread = new Thread(this::runLoop, THREAD_NAME);
        thread.setDaemon(false);
        thread.start();
    }

    /**
     * Signals the loop to stop claiming new jobs once it next checks. Deliberately does <em>not</em>
     * interrupt the thread: architecture §9 requires a near-done generation to be allowed to finish
     * within the grace window, and interrupting unconditionally here would abort an in-flight llama call
     * or result-redelivery attempt that was about to succeed on its own. A poll-sleep between jobs is
     * bounded by {@code pollIntervalMs} anyway, so shutdown is noticed promptly without needing to
     * interrupt; {@link #abandonCurrentJob()} is the (later, explicit) point where interrupting becomes
     * appropriate.
     */
    public void requestShutdown() {
        shuttingDown.set(true);
    }

    /** Blocks up to {@code timeout} for the loop thread to exit. Returns {@code true} if it did. */
    public boolean awaitTermination(Duration timeout) {
        Thread t = thread;
        if (t == null) {
            return true;
        }
        try {
            t.join(Math.max(timeout.toMillis(), 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /**
     * Forces the currently in-flight job (if any) to abort: used by {@code GracefulShutdown} once the
     * shutdown grace period has genuinely elapsed with a job still running. Unlike
     * {@link #requestShutdown()}, this deliberately also interrupts the loop thread directly -- by this
     * point waiting further is no longer appropriate, so the interrupt is needed both to cancel the
     * llama future (belt-and-suspenders alongside {@link AbortSignal#abort()}) and to wake up a blocked
     * {@code claim} call or a result-redelivery backoff sleep. Safe to call when no job is in flight.
     */
    public void abandonCurrentJob() {
        AbortSignal signal = currentAbortSignal;
        if (signal != null) {
            log.warn("Forcing abandonment of the in-flight job (shutdown grace period elapsed)");
            signal.abort();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runLoop() {
        long backoffMs = 0;
        try {
            while (!shuttingDown.get()) {
                Optional<ClaimResponse> claimed;
                try {
                    claimed = gatewayClient.claim(properties.getBackend().getId(), properties.getWorker().getId());
                    backoffMs = 0;
                } catch (GatewayUnavailableException e) {
                    metrics.incrementGatewayErrors();
                    backoffMs = nextBackoff(backoffMs);
                    log.warn("Gateway unavailable while claiming a job; backing off {} ms", backoffMs, e);
                    sleepInterruptibly(backoffMs);
                    continue;
                }

                if (shuttingDown.get()) {
                    break;
                }

                if (claimed.isEmpty()) {
                    recordIdlePoll();
                    sleepInterruptibly(properties.getNetwork().getPollIntervalMs());
                    continue;
                }

                idlePollCount = 0;
                processJob(claimed.get());
            }
        } finally {
            running.set(false);
            log.info("worker-loop thread exiting");
        }
    }

    private long nextBackoff(long previousMs) {
        long base = Math.max(properties.getNetwork().getPollIntervalMs(), 1);
        long next = previousMs <= 0 ? base : previousMs * 2;
        return Math.min(next, MAX_BACKOFF_MS);
    }

    /**
     * WOC-04: rate-limited idle-liveness summary. Fires at most once per {@code
     * worker.log.idle-summary-interval-sec} (default 300s; {@code 0} disables it) so a genuinely idle
     * Worker still proves liveness in its log without spamming one line per poll.
     */
    private void recordIdlePoll() {
        idlePollCount++;
        int intervalSec = properties.getWorker().getLog().getIdleSummaryIntervalSec();
        if (intervalSec <= 0) {
            return;
        }
        long intervalMs = intervalSec * 1000L;
        long now = System.currentTimeMillis();
        if (lastIdleSummaryAtMillis == 0) {
            lastIdleSummaryAtMillis = now;
            return;
        }
        if (now - lastIdleSummaryAtMillis >= intervalMs) {
            log.info("Idle: no job available in the last {} poll(s) (backend={})",
                    idlePollCount, properties.getBackend().getId());
            idlePollCount = 0;
            lastIdleSummaryAtMillis = now;
        }
    }

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(Math.max(millis, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processJob(ClaimResponse job) {
        metrics.incrementJobsTotal();
        String workerId = properties.getWorker().getId();
        AbortSignal abortSignal = new AbortSignal();
        currentAbortSignal = abortSignal;
        try {
            ResolvedPrompt prompt = promptTemplateService.resolve(job.payload().promptVersion(),
                    job.payload().diff(), job.payload().chunkContext(), job.payload().systemMessages());
            // Structured Review Output (SRO-13): resolved directly from the claim payload, never through
            // PromptTemplateService/substitute() (SOR-07) -- the constraint is transport, not template
            // input. Resolved before the heartbeat starts, same as prompt resolution above, so an invalid
            // constraint never wastes a heartbeat cycle.
            DecoderConstraint constraint = decoderConstraintResolver.resolve(
                    job.payload().responseFormat(), job.payload().jsonSchema());

            heartbeatScheduler.start(job.jobId(), workerId, abortSignal);
            try {
                runInference(job, workerId, prompt, constraint, abortSignal);
            } finally {
                heartbeatScheduler.stop();
            }
        } catch (AbandonJobException | LlamaException e) {
            JobFailureReason reason = classifyFailure(e);
            // WOR-05: never e.getMessage() here -- a wrapped Jackson parse-failure message can quote
            // LLM-generated JSON verbatim (WOT-03). Only the classified reason and the exception's class
            // name (never its message) are logged.
            log.warn("Job abandoned (jobId={}, reason={}, exceptionType={})",
                    job.jobId(), reason, e.getClass().getSimpleName());
            metrics.incrementJobsFailed();
            // WOC-34/WOC-37: heartbeatScheduler.stop() has already run (inner finally, above) and the
            // job was not aborted/superseded (an abort/redelivery-abandon/shutdown path never throws
            // here -- see those call sites) -- safe and correct to report now, synchronously, before the
            // loop returns to claim().
            reportFailureBestEffort(job.jobId(), workerId, reason);
        } finally {
            currentAbortSignal = null;
        }
    }

    private JobFailureReason classifyFailure(RuntimeException e) {
        if (e instanceof LlamaException llamaException) {
            return llamaException.getReason();
        }
        if (e instanceof AbandonJobException abandonJobException) {
            return abandonJobException.getReason();
        }
        return JobFailureReason.WORKER_ERROR;
    }

    /**
     * WOC-35: single best-effort attempt, no redelivery loop, no backoff, no retry of its own. Any
     * failure is logged at WARN, counted in {@code worker.gateway.errors}, and swallowed -- the
     * Gateway's stale-heartbeat sweep remains the correctness backstop (WOC-36); nothing in the system
     * may become dependent on this report being delivered.
     *
     * <p><b>F-WOC-03:</b> the catch is deliberately {@link Throwable}, not just {@link
     * GatewayUnavailableException} -- {@code GatewayClient.reportFailure} maps only {@code
     * RestClientResponseException}/{@code ResourceAccessException} into that type, so any other
     * unmapped exception from this call (an unrelated {@code RestClientException} subtype, an {@code
     * IllegalArgumentException} from URI templating, etc.) would otherwise propagate out of {@code
     * processJob}'s only handler and out of {@code runLoop}'s {@code while}, killing the worker-loop
     * thread -- exactly the silent-stall failure class WOC-35 exists to eliminate (a wedged/dead Worker
     * that logs nothing further and claims no more jobs). Same precedent as {@code
     * HeartbeatScheduler.tick}'s WSR-15 crash guard.
     */
    private void reportFailureBestEffort(long jobId, String workerId, JobFailureReason reason) {
        try {
            gatewayClient.reportFailure(jobId, new FailRequest(workerId, reason.name(), DETAIL_BY_REASON.get(reason)));
            metrics.incrementFailuresReported();
        } catch (Throwable t) {
            metrics.incrementGatewayErrors();
            log.warn("Failed to report job failure to the Gateway; the stale-heartbeat sweep will recover "
                    + "it (jobId={})", jobId, t);
        }
    }

    private void runInference(ClaimResponse job, String workerId, ResolvedPrompt prompt, DecoderConstraint constraint,
                               AbortSignal abortSignal) {
        // WOC-03/WOR-17: sizes/counts only, never the raw systemMessages/messages content -- closes the
        // gap between "Job claimed" and the first heartbeat tick (up to 60s of silence today).
        int diffChars = job.payload().diff() == null ? 0 : job.payload().diff().length();
        int systemMessageCount = job.payload().systemMessages() == null ? 0 : job.payload().systemMessages().size();
        log.info("Starting inference (jobId={}, reviewId={}, diffChars={}, systemMessages={}, model={}, maxTokens={})",
                job.jobId(), job.reviewId(), diffChars, systemMessageCount, prompt.model(), prompt.maxTokens());

        LlamaClient.AsyncCompletion call = llamaClient.startChatCompletion(
                prompt.messages(), prompt.model(), prompt.temperature(), prompt.maxTokens(), constraint);
        abortSignal.attach(call.future());

        HttpResponse<InputStream> httpResponse = awaitLlamaResponse(call, abortSignal);

        if (abortSignal.isAborted() || httpResponse == null) {
            // D6/§6: the job was cancelled/superseded mid-generation -- submit nothing, no metric either
            // way (this is neither a Worker-side completion nor a Worker-side failure).
            log.info("Job aborted before/while awaiting llama response; submitting nothing (jobId={})", job.jobId());
            return;
        }

        long durationMs = System.currentTimeMillis() - call.startedAtMillis();
        LlamaResult result = llamaClient.parseResponse(httpResponse, prompt.model(), durationMs);
        metrics.recordLlamaDuration(Duration.ofMillis(result.durationMs()));

        if (abortSignal.isAborted()) {
            log.info("Job aborted after the llama response arrived; discarding it (jobId={})", job.jobId());
            return;
        }

        RedeliveryOutcome redeliveryOutcome = submitResultWithRedelivery(job.jobId(), workerId, result);
        if (redeliveryOutcome == RedeliveryOutcome.DELIVERED) {
            metrics.incrementJobsCompleted();
        } else {
            // QA defect fix: an interrupted/abandoned redelivery (e.g. GracefulShutdown's grace period
            // elapsing mid-backoff) must never be counted as completed -- the Gateway never actually
            // acknowledged the result, so from an observability standpoint this job did not succeed.
            log.warn("Result redelivery abandoned before the Gateway ever acknowledged it; counting job "
                    + "as failed, not completed (jobId={})", job.jobId());
            metrics.incrementJobsFailed();
        }
    }

    /**
     * Awaits the llama response, bounded by {@code requestTimeoutSec}. Returns {@code null} if the call
     * was aborted (the abort race is inherently ambiguous: depending on exactly when
     * {@code future.cancel(true)} lands relative to the JDK {@code HttpClient}'s internal exchange state,
     * a cancelled call can surface as a {@link CancellationException}, an {@link ExecutionException}, or
     * (rarely) a {@link TimeoutException} that happens to race with the cancellation — so
     * {@link AbortSignal#isAborted()} is checked in <em>every</em> catch branch and takes precedence over
     * classifying the failure: an aborted job is never counted as a Worker-side failure, only a genuine,
     * non-aborted llama error is.
     */
    private HttpResponse<InputStream> awaitLlamaResponse(LlamaClient.AsyncCompletion call, AbortSignal abortSignal) {
        try {
            return call.future().get(properties.getNetwork().getRequestTimeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            call.future().cancel(true);
            if (abortSignal.isAborted()) {
                return null;
            }
            throw new LlamaException("llama-server did not respond within requestTimeoutSec", e, JobFailureReason.LLM_TIMEOUT);
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            if (abortSignal.isAborted()) {
                return null;
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new LlamaException("llama-server call failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.future().cancel(true);
            if (abortSignal.isAborted()) {
                return null;
            }
            throw new LlamaException("Interrupted while awaiting llama-server response", e);
        }
    }

    /** Outcome of {@link #submitResultWithRedelivery}, driving whether the job counts as completed or failed. */
    private enum RedeliveryOutcome {
        /** The Gateway responded (200/403/404 — all terminal, idempotent-acknowledged outcomes). */
        DELIVERED,
        /** Redelivery was interrupted/abandoned before the Gateway ever acknowledged the result. */
        ABANDONED
    }

    /**
     * Transport-level redelivery of an already-computed, idempotent result (architecture §7): retries
     * with capped exponential backoff until the Gateway accepts it, rejects it as not-owned/not-found
     * (both terminal from the Worker's perspective, and both count as {@link RedeliveryOutcome#DELIVERED}
     * — the Gateway *processed* the request either way, per {@code GatewayClient.submitResult}'s existing
     * 200/403/404 semantics, which this method does not change), or this thread is interrupted (shutdown
     * abandoning it, {@link RedeliveryOutcome#ABANDONED}). Never re-invokes the LLM -- this is not
     * business retry.
     */
    private RedeliveryOutcome submitResultWithRedelivery(long jobId, String workerId, LlamaResult result) {
        ResultRequest request = new ResultRequest(workerId, result.rawResponse(), result.promptTokens(),
                result.completionTokens(), result.durationMs(), result.model(), result.finishReason());
        long backoffMs = 0;
        while (true) {
            try {
                ResultOutcome outcome = gatewayClient.submitResult(jobId, request);
                log.info("Result delivered (jobId={}, status={})", jobId, outcome.status());
                return RedeliveryOutcome.DELIVERED;
            } catch (GatewayUnavailableException e) {
                metrics.incrementGatewayErrors();
                backoffMs = nextBackoff(backoffMs);
                log.warn("Gateway unavailable while submitting result; retrying in {} ms (jobId={})",
                        backoffMs, jobId, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while redelivering result; giving up (jobId={})", jobId);
                    return RedeliveryOutcome.ABANDONED;
                }
            }
        }
    }
}
