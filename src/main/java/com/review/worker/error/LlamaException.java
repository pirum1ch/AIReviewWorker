package com.review.worker.error;

/**
 * Raised for any failure while calling the local llama-server: a 5xx status, a malformed/unparseable
 * body, a connection error, or a response that exceeds {@code worker.limits.max-response-bytes} (WSR-04/
 * WSR-05 — the oversize case is deliberately represented here rather than as a distinct exception type:
 * every {@code LlamaException}, regardless of the specific cause, results in the same "abandon this job"
 * handling one level up (architecture §6/§7 error taxonomy), so a separate type would add no behavioral
 * distinction).
 *
 * <p><b>WOC-22 (Worker Observability &amp; Claim Latency):</b> carries a {@link JobFailureReason},
 * defaulting to {@link JobFailureReason#LLM_ERROR} on the two-arg constructors so no existing throw site
 * is forced to change; specific throw sites that know a more precise classification (empty response,
 * timeout, oversized body) use the reason-carrying constructors instead. {@code WorkerLoop} reads this
 * via {@link #getReason()} to classify the {@code POST /jobs/{id}/fail} report (WOC-34) — it never derives
 * that report's {@code detail} field from {@link #getMessage()} (WOR-05).
 */
public class LlamaException extends RuntimeException {

    private final JobFailureReason reason;

    public LlamaException(String message) {
        this(message, JobFailureReason.LLM_ERROR);
    }

    public LlamaException(String message, Throwable cause) {
        this(message, cause, JobFailureReason.LLM_ERROR);
    }

    public LlamaException(String message, JobFailureReason reason) {
        super(message);
        this.reason = reason;
    }

    public LlamaException(String message, Throwable cause, JobFailureReason reason) {
        super(message, cause);
        this.reason = reason;
    }

    public JobFailureReason getReason() {
        return reason;
    }
}
