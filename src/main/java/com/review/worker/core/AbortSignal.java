package com.review.worker.core;

import java.util.concurrent.Future;

/**
 * Cross-thread coordination between {@link HeartbeatScheduler} (which detects "stop this job" from the
 * Gateway) and {@link WorkerLoop} (which is blocked waiting on the llama call): a {@code volatile} flag
 * plus a handle on the in-flight, cancellable future (architecture §5/§11).
 *
 * <p>One instance per job; created fresh by {@link WorkerLoop} for each claimed job and discarded once
 * the job is done (never reused across jobs), so there is no need for it to be resettable.
 */
public final class AbortSignal {

    private volatile boolean aborted;
    private Future<?> future;

    /**
     * Registers the future that {@link #abort()} should cancel once it exists. Safe to call after
     * {@link #abort()} has already fired (e.g. the heartbeat aborted before the llama call was even
     * issued): in that case the newly-attached future is cancelled immediately.
     *
     * <p>Synchronized (together with {@link #abort()}) so a heartbeat-thread {@code abort()} racing with
     * the job-loop-thread {@code attach()} can never leave the future both un-cancelled and unreachable.
     */
    public synchronized void attach(Future<?> future) {
        this.future = future;
        if (aborted) {
            future.cancel(true);
        }
    }

    /** Sets the abort flag and, if a future is already attached, cancels it immediately. */
    public synchronized void abort() {
        aborted = true;
        if (future != null) {
            future.cancel(true);
        }
    }

    public boolean isAborted() {
        return aborted;
    }
}
