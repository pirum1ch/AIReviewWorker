package com.review.worker.error;

/**
 * Raised when the Review Gateway cannot be reached or returns a server error (5xx) while claiming a
 * job, sending a heartbeat, or submitting a result. Per the architecture's error taxonomy (§7), this is
 * never fatal: the Worker keeps running, backs off, and retries — it must never exit the process on a
 * transient Gateway outage.
 */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
