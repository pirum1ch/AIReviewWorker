package com.review.worker.lifecycle;

import com.review.worker.config.WorkerProperties;
import com.review.worker.core.CappedBackoff;
import com.review.worker.core.WorkerLoop;
import com.review.worker.error.GatewayUnavailableException;
import com.review.worker.gateway.AnnounceOutcome;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.dto.AnnounceRequest;
import com.review.worker.gateway.dto.AnnounceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the {@code worker-loop} thread once the application context has finished starting up — and,
 * for Backend Self-Registration (architecture §4.3, threat model BSQ-18/19/20), announces this backend
 * to the Gateway first when {@code backend.url} is configured.
 *
 * <p>{@code WorkerRunner} runs on the main thread, synchronously, inside {@code SpringApplication.run()}
 * — after context refresh (so {@code /actuator/health} liveness already answers) but before Spring Boot
 * publishes {@code ReadinessState.ACCEPTING_TRAFFIC}, so a Worker that has not yet announced reports
 * readiness {@code OUT_OF_SERVICE} for free. Because this runs before the context is fully "started", a
 * plain infinite retry loop here would <b>not</b> be interruptible by the JVM's normal shutdown hook: the
 * hook thread calls {@code context.close()}, which publishes {@link ContextClosedEvent} on that same
 * shutdown-hook thread (registered before {@code callRunners()} — verified against Spring Boot's own
 * {@code SpringApplication.refreshContext}/{@code run} sequence), while this thread would otherwise keep
 * sleeping through a backoff step none the wiser — a {@code SIGTERM} against a Worker retrying at a down
 * Gateway would hang until {@code SIGKILL}. Listening for {@link ContextClosedEvent} here and sleeping in
 * short slices (BSQ-19) closes that gap.
 */
@Component
public class WorkerRunner implements ApplicationRunner, ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    /** BSQ-19: no single sleep may block longer than this before re-checking the shutdown signal. */
    private static final long SHUTDOWN_CHECK_SLICE_MS = 200L;

    private final WorkerLoop workerLoop;
    private final GatewayClient gatewayClient;
    private final WorkerProperties properties;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public WorkerRunner(WorkerLoop workerLoop, GatewayClient gatewayClient, WorkerProperties properties) {
        this.workerLoop = workerLoop;
        this.gatewayClient = gatewayClient;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown.set(true);
    }

    @Override
    public void run(ApplicationArguments args) {
        String backendUrl = properties.getBackend().getUrl();
        if (backendUrl == null || backendUrl.isBlank()) {
            log.info("Backend self-registration disabled (backend.url not set); this backend must be "
                    + "registered via the Gateway's admin API or SQL");
            log.info("Starting worker-loop");
            workerLoop.start();
            return;
        }

        if (!announceWithRetry(backendUrl)) {
            // Shutdown was requested while still retrying against an unreachable Gateway -- the context is
            // already closing, so there is nothing left to start (BSQ-19).
            log.info("Shutdown requested during backend announce; not starting worker-loop");
            return;
        }

        log.info("Starting worker-loop");
        workerLoop.start();
    }

    /**
     * @return {@code true} once the announce succeeded (accepted, or a non-fatal rejection to fall back to
     *     legacy mode) and the caller should proceed to {@code workerLoop.start()}; {@code false} if
     *     shutdown was signalled mid-retry.
     * @throws IllegalStateException on a fatal (409/422) rejection — a genuine misconfiguration that will
     *     never self-heal (BSQ-18).
     */
    private boolean announceWithRetry(String backendUrl) {
        AnnounceRequest request = new AnnounceRequest(
                properties.getBackend().getId(),
                properties.getWorker().getId(),
                backendUrl,
                properties.getLlama().getModel());

        long backoffMs = 0;
        while (!shuttingDown.get()) {
            AnnounceOutcome outcome;
            try {
                outcome = gatewayClient.announce(request);
            } catch (GatewayUnavailableException e) {
                backoffMs = CappedBackoff.next(backoffMs, properties.getNetwork().getPollIntervalMs());
                log.warn("Gateway unavailable while announcing this backend; retrying in {} ms", backoffMs, e);
                if (!sleepInSlices(backoffMs)) {
                    return false;
                }
                continue;
            }
            return handleOutcome(outcome);
        }
        return false;
    }

    private boolean handleOutcome(AnnounceOutcome outcome) {
        switch (outcome.status()) {
            case ACCEPTED -> {
                AnnounceResponse response = outcome.response();
                String status = response == null ? null : response.status();
                String name = response == null ? properties.getBackend().getId() : response.name();
                boolean created = response != null && response.created();
                log.info("Backend registered (name={}, status={}, created={})", name, status, created);
                if (status != null && !"ACTIVE".equalsIgnoreCase(status)) {
                    log.warn("Backend {} registered with status={} -- no jobs will be dispatched until an "
                            + "operator reactivates it", name, status);
                }
                return true;
            }
            // BSQ-18/BST-14a: 403/404 are the normal "this Gateway doesn't do self-registration" case --
            // functionally identical to BSR-17's legacy mode. Treating it as fatal would turn a Gateway
            // operator's kill switch into a fleet-wide Worker crash-loop, so this WARNs and proceeds.
            case REJECTED_NONFATAL -> {
                int statusCode = outcome.statusCode();
                String cause = statusCode == 403
                        ? "Gateway's self-registration.enabled is off, or GATEWAY_API_KEY is not the WORKER token"
                        : "Gateway build predates this endpoint";
                log.warn("Backend announce rejected ({}): {} -- continuing in legacy mode "
                        + "(register this backend via the Gateway's admin API or SQL)", statusCode, cause);
                return true;
            }
            // 409/422 are this Worker's own misconfiguration and will never self-heal -- fail startup loudly.
            case REJECTED_FATAL -> {
                int statusCode = outcome.statusCode();
                String cause = statusCode == 409
                        ? "backend.id is already owned by a different worker.id on the Gateway (409) -- "
                                + "check for a copy-pasted BACKEND_ID across hosts"
                        : "backend.url was rejected by the Gateway's host allowlist, or was not a bare "
                                + "origin (422)";
                throw new IllegalStateException("Backend self-registration failed (" + statusCode + "): " + cause);
            }
            default -> throw new IllegalStateException("Unexpected announce outcome: " + outcome.status());
        }
    }

    /**
     * Sleeps up to {@code totalMs}, in slices no longer than {@link #SHUTDOWN_CHECK_SLICE_MS}, re-checking
     * the shutdown signal between slices (BSQ-19).
     *
     * @return {@code false} if shutdown was signalled or the thread was interrupted before the full sleep
     *     elapsed; {@code true} if the full duration elapsed without either.
     */
    private boolean sleepInSlices(long totalMs) {
        long remaining = totalMs;
        while (remaining > 0) {
            if (shuttingDown.get()) {
                return false;
            }
            long slice = Math.min(remaining, SHUTDOWN_CHECK_SLICE_MS);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return !shuttingDown.get();
    }
}
