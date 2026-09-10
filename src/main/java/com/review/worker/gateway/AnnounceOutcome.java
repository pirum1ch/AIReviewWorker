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
 * because an operator flipped the Gateway's kill switch off. {@code 409}/{@code 422} are
 * {@link AnnounceStatus#REJECTED_FATAL} — a genuine, non-self-healing Worker-side misconfiguration
 * ({@code backend.id} already owned by a different {@code worker.id}, or {@code backend.url} rejected
 * by the Gateway's host allowlist / not a bare origin).
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
