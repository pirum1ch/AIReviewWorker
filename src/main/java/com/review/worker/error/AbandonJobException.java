package com.review.worker.error;

/**
 * Signals that the current job must be abandoned <em>before</em> (or without) submitting any result:
 * an unknown/invalid {@code promptVersion}, an oversized diff, or any other pre-flight condition that
 * makes it unsafe or meaningless to even attempt calling llama-server (architecture doc D6 — "no
 * synthetic error result is ever submitted"; the Gateway's own stale-heartbeat sweep reclaims an
 * abandoned job after ~180s, and — as of Worker Observability &amp; Claim Latency — {@code WorkerLoop}
 * also reports it explicitly via {@code POST /jobs/{id}/fail}, WOC-34, for a much faster recovery).
 *
 * <p>Never carries the offending content (diff/promptVersion value) verbatim in a way that would leak
 * Gateway-supplied data into logs beyond what the caller explicitly chooses to log (WSR-10/WSR-18).
 *
 * <p><b>WOC-22:</b> carries a {@link JobFailureReason}, defaulting to {@link JobFailureReason#PROMPT_INVALID}
 * on the two-arg constructors (every current {@code PromptTemplateService} throw site is exactly that).
 */
public class AbandonJobException extends RuntimeException {

    private final JobFailureReason reason;

    public AbandonJobException(String message) {
        this(message, JobFailureReason.PROMPT_INVALID);
    }

    public AbandonJobException(String message, Throwable cause) {
        this(message, cause, JobFailureReason.PROMPT_INVALID);
    }

    public AbandonJobException(String message, JobFailureReason reason) {
        super(message);
        this.reason = reason;
    }

    public AbandonJobException(String message, Throwable cause, JobFailureReason reason) {
        super(message, cause);
        this.reason = reason;
    }

    public JobFailureReason getReason() {
        return reason;
    }
}
