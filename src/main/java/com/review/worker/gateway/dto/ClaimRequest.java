package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.ClaimRequest} field-for-field. */
public record ClaimRequest(String backendId, String workerId) {
}
