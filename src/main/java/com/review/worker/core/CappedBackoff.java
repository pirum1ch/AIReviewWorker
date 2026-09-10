package com.review.worker.core;

/**
 * The capped-exponential-backoff schedule shared by every Gateway-unreachable retry loop in this
 * process: {@link WorkerLoop}'s claim loop and {@code lifecycle.WorkerRunner}'s pre-startup announce
 * retry (BSQ-19/BST-14b, {@code backend-self-registration-threat-model.md}). One algorithm, reused
 * across both call sites rather than reimplemented per caller.
 */
public final class CappedBackoff {

    /** Upper bound on any single backoff step. */
    public static final long MAX_BACKOFF_MS = 60_000L;

    private CappedBackoff() {
    }

    /**
     * Doubles {@code previousMs} on each call (starting at {@code baseMs} the first time,
     * {@code previousMs <= 0}), capped at {@link #MAX_BACKOFF_MS}. {@code baseMs} is floored at 1 so a
     * misconfigured {@code 0}/negative poll interval can never produce a zero-length backoff step.
     */
    public static long next(long previousMs, long baseMs) {
        long base = Math.max(baseMs, 1);
        long next = previousMs <= 0 ? base : previousMs * 2;
        return Math.min(next, MAX_BACKOFF_MS);
    }
}
