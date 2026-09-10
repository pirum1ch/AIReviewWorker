package com.review.worker.gateway.dto;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.AnnounceBackendRequest} field-for-field
 * (Backend Self-Registration, architecture §4.2). {@code url} is {@code backend.url} — the externally
 * reachable address the Gateway should health-probe this backend at, distinct from {@code llama.url}
 * (where this Worker process itself connects). No {@code toString()} override: none of these four
 * fields is attacker-influenced free text of unbounded size (unlike {@code ResultRequest#rawResponse}).
 */
public record AnnounceRequest(String backendId, String workerId, String url, String model) {
}
