package com.review.worker.error;

/**
 * Closed-set classification of a Worker-side job failure, sent as the {@code reason} field of {@code
 * POST /jobs/{id}/fail} (architecture §5.3, WOC-22). Audit-only on the Gateway side (WOC-24: never
 * influences the retry-vs-fail decision) — mirrors {@code com.review.gateway.model.enums.JobFailureReason}
 * by name so the wire value round-trips, but the two enums are intentionally independent types (the
 * Gateway treats the wire value as untrusted and whitelist-parses it, WOC-23; it never shares a class
 * with this one).
 */
public enum JobFailureReason {
    LLM_EMPTY_RESPONSE,
    LLM_ERROR,
    LLM_TIMEOUT,
    LLM_RESPONSE_TOO_LARGE,
    PROMPT_INVALID,
    WORKER_ERROR,
    /**
     * Structured Review Output (SRO-13): the Gateway-supplied {@code responseFormat}/{@code jsonSchema}
     * failed this Worker's own defensive re-check — both fields set, oversized (before {@code readTree},
     * SOR-06), not valid JSON, or not a JSON object. A defensive bound against a misbehaving/compromised
     * Gateway, exactly like {@code worker.limits.max-diff-bytes} (WSR-03).
     */
    CONSTRAINT_INVALID,
    /**
     * Structured Output Grammar Budget (SGB-06, threat model SOGT-03/SOGB-05..08): {@code LlamaClient}
     * classified a non-2xx llama-server response as a compile-time grammar rejection (a fixed,
     * case-insensitive, backend-controlled error-body token match against a small {@code static final}
     * list — never a regex, never the matched text itself). Audit-only, same as every other value here —
     * {@code WorkerLoop.DETAIL_BY_REASON} carries a fixed Worker-side sentence for it, never the
     * backend's actual error text (WOR-05).
     */
    CONSTRAINT_REJECTED
}
