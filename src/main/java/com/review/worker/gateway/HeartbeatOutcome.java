package com.review.worker.gateway;

/**
 * Typed result of {@link GatewayClient#heartbeat(long, String)}. The architecture's {@code error/}
 * package inventory (§11) lists exactly three exception types, none of which represent a 403/404 from
 * the Gateway (those are ordinary, expected outcomes — a job can legitimately go stale/reassigned/
 * cancelled between one heartbeat and the next) — so 403/404 are modeled as data here rather than as
 * additional exception types.
 */
public record HeartbeatOutcome(HeartbeatStatus status, boolean shouldContinue) {

    public static HeartbeatOutcome accepted(boolean shouldContinue) {
        return new HeartbeatOutcome(HeartbeatStatus.ACCEPTED, shouldContinue);
    }

    public static HeartbeatOutcome notFound() {
        return new HeartbeatOutcome(HeartbeatStatus.NOT_FOUND, false);
    }

    public static HeartbeatOutcome forbidden() {
        return new HeartbeatOutcome(HeartbeatStatus.FORBIDDEN, false);
    }

    public enum HeartbeatStatus {
        ACCEPTED,
        NOT_FOUND,
        FORBIDDEN
    }
}
