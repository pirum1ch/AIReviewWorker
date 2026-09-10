package com.review.worker.gateway;

import com.review.worker.gateway.dto.AnnounceResponse;

/**
 * Typed result of {@link GatewayClient#announce(com.review.worker.gateway.dto.AnnounceRequest)}. See
 * {@link HeartbeatOutcome} for why the Gateway's non-2xx responses are modeled as data rather than as
 * exceptions.
 *
 * <p>Backend Self-Registration threat model BSQ-18/BST-14a: {@code 403}/{@code 404} are deliberately
 * {@link AnnounceStatus#REJECTED_NONFATAL} — "this Gateway does not do self-registration" is the normal
 * legacy-mode case, not a fatal Worker misconfiguration, and must never crash-loop the fleet just
 * because an operator flipped the Gateway's kill switch off.
 *
 * <p><b>F-BSR-05:</b> every other {@code 4xx} (including {@code 400}/{@code 401}/{@code 409}/{@code 422},
 * but not limited to that enumerated set) is {@link AnnounceStatus#REJECTED_FATAL} by default — a genuine,
 * non-self-healing Worker-side condition ({@code backend.id}/{@code worker.id}/{@code llama.model} failing
 * the Gateway's bean validation, a wrong/missing bearer token, {@code backend.id} already owned by a
 * different {@code worker.id}, or {@code backend.url} rejected by the Gateway's host allowlist / not a
 * bare origin). {@code 429} is the sole exception (genuinely transient, retried like a {@code 5xx}) —
 * see {@link GatewayClient#announce}'s javadoc for the full rule and why the default was inverted from
 * the pre-fix "retry forever unless explicitly fatal".
 */
public record AnnounceOutcome(AnnounceStatus status, AnnounceResponse response, Integer statusCode) {

    public static AnnounceOutcome accepted(AnnounceResponse response) {
        return new AnnounceOutcome(AnnounceStatus.ACCEPTED, response, null);
    }

    public static AnnounceOutcome rejectedNonFatal(int statusCode) {
        return new AnnounceOutcome(AnnounceStatus.REJECTED_NONFATAL, null, statusCode);
    }

    public static AnnounceOutcome rejectedFatal(int statusCode) {
        return new AnnounceOutcome(AnnounceStatus.REJECTED_FATAL, null, statusCode);
    }

    public enum AnnounceStatus {
        ACCEPTED,
        REJECTED_NONFATAL,
        REJECTED_FATAL
    }
}
