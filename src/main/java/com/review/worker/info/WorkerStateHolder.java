package com.review.worker.info;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the Worker's runtime status and the last-completion performance snapshot,
 * consumed by {@link WorkerInfoService} to serve REQ-6 and REQ-7 endpoints.
 *
 * <p>Written by {@link com.review.worker.core.WorkerLoop} from its single loop thread and read by the
 * info-server Tomcat threads — {@link AtomicReference} gives the required visibility without blocking
 * either side.
 */
@Component
public class WorkerStateHolder {

    public enum Status { STARTING, IDLE, RUNNING, ERROR }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.STARTING);

    /**
     * Atomic pair: both fields update together so callers never see a stale durationMs paired with a
     * fresh completionTokens from a different job.
     */
    record LastCompletion(long completionTokens, long durationMs) {}

    private final AtomicReference<LastCompletion> lastCompletion = new AtomicReference<>(null);

    public void markIdle()    { status.set(Status.IDLE); }
    public void markRunning() { status.set(Status.RUNNING); }
    public void markError()   { status.set(Status.ERROR); }

    /**
     * Records the most recent successful completion for tok/s calculation.
     * Silently ignored when {@code completionTokens} is null/zero or {@code durationMs} is non-positive
     * (both indicate an unusable sample — guard prevents divide-by-zero).
     */
    public void recordCompletion(Integer completionTokens, long durationMs) {
        if (completionTokens == null || completionTokens <= 0 || durationMs <= 0) {
            return;
        }
        lastCompletion.set(new LastCompletion(completionTokens, durationMs));
    }

    public Status getStatus() {
        return status.get();
    }

    /**
     * Returns tokens/second from the last completed job, or {@code null} if no job has completed yet.
     */
    public Double currentTokPerSec() {
        LastCompletion lc = lastCompletion.get();
        if (lc == null) {
            return null;
        }
        return lc.completionTokens() / (lc.durationMs() / 1000.0);
    }
}
