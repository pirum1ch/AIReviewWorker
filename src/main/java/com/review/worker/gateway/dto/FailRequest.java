package com.review.worker.gateway.dto;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.FailJobRequest} field-for-field (architecture
 * §5.2). {@code detail} — when non-{@code null} — is always a fixed, Worker-side constant per failure
 * class (see {@code WorkerLoop}'s {@code DETAIL_BY_REASON}), never an exception message (WOR-05): this
 * type carries no {@code toString()} override because, unlike {@code ResultRequest}, nothing on it is
 * ever attacker-influenced free text of unbounded size.
 */
public record FailRequest(String workerId, String reason, String detail) {
}
