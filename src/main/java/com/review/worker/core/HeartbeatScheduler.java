package com.review.worker.core;

import com.review.worker.config.WorkerProperties;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.HeartbeatOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a single-thread {@code ScheduledExecutorService} ("worker-heartbeat") for the lifetime of one
 * job: on {@code shouldContinue:false}/{@code 403}/{@code 404} it sets the job's {@link AbortSignal} and
 * cancels the in-flight llama future (architecture §5/§6). A fresh executor is created per job by
 * {@link #start} and torn down by {@link #stop}, matching "active only for the duration of a job".
 *
 * <p>WSR-15 (MUST): a heartbeat tick must never silently kill the scheduler thread. Every tick is wrapped
 * so that <em>any</em> {@link Throwable} — not just the checked/unchecked exceptions the Gateway call can
 * throw — is caught, logged, and counted; {@code scheduleAtFixedRate} would otherwise suppress all future
 * executions the moment one tick throws, leaving the job heartbeat-less and silently running forever (a
 * zombie). As a fail-safe, {@value #MAX_CONSECUTIVE_FAILURES} consecutive tick failures abort the job
 * outright — better to abandon a job than keep running blind with no way to learn the Gateway wants it
 * stopped.
 */
@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final GatewayClient gatewayClient;
    private final WorkerProperties properties;

    private ScheduledExecutorService executor;

    public HeartbeatScheduler(GatewayClient gatewayClient, WorkerProperties properties) {
        this.gatewayClient = gatewayClient;
        this.properties = properties;
    }

    /** Starts a fresh heartbeat cadence for one job. Calling this while already running restarts it. */
    public synchronized void start(long jobId, String workerId, AbortSignal abortSignal) {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "worker-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger consecutiveFailures = new AtomicInteger();
        // WOC-02: process-local logging state, scoped to this one job's heartbeat cadence -- created
        // here (alongside consecutiveFailures) so the public start/stop signature does not change and no
        // caller is touched.
        long startedAtMillis = System.currentTimeMillis();
        AtomicInteger tickCount = new AtomicInteger();
        long intervalSec = properties.getHeartbeat().getIntervalSec();
        ScheduledFuture<?> ignored = executor.scheduleAtFixedRate(
                () -> tick(jobId, workerId, abortSignal, consecutiveFailures, startedAtMillis, tickCount),
                intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    /** Stops the heartbeat cadence. Safe to call even if never started, and safe to call more than once. */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Package-private so unit tests can exercise one tick synchronously, without waiting on a real schedule. */
    void tick(long jobId, String workerId, AbortSignal abortSignal, AtomicInteger consecutiveFailures,
              long startedAtMillis, AtomicInteger tickCount) {
        try {
            HeartbeatOutcome outcome = gatewayClient.heartbeat(jobId, workerId);
            consecutiveFailures.set(0);
            switch (outcome.status()) {
                case ACCEPTED -> {
                    if (outcome.shouldContinue()) {
                        // WOC-02: the normal-case, once-a-minute "still alive and working" signal that
                        // was previously silent for up to request-timeout-sec (1800s) of a busy Worker.
                        int ticks = tickCount.incrementAndGet();
                        long elapsedSec = (System.currentTimeMillis() - startedAtMillis) / 1000L;
                        log.info("Job in progress (jobId={}, workerId={}, elapsedSec={}, heartbeats={})",
                                jobId, workerId, elapsedSec, ticks);
                    } else {
                        log.info("Gateway requested this job stop (jobId={})", jobId);
                        abortSignal.abort();
                    }
                }
                case NOT_FOUND, FORBIDDEN -> {
                    log.info("Heartbeat rejected ({}); aborting job (jobId={})", outcome.status(), jobId);
                    abortSignal.abort();
                }
            }
        } catch (Throwable t) {
            // WSR-15: this catch is the whole point of the requirement -- it must never be narrowed to a
            // specific exception type, or an unanticipated failure would silently stop the heartbeat.
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("Heartbeat tick failed ({} consecutive) (jobId={})", failures, jobId, t);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                log.error("Heartbeat failed {} times in a row (jobId={}); aborting fail-safe rather than "
                        + "running blind", failures, jobId);
                abortSignal.abort();
            }
        }
    }
}
