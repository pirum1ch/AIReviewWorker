package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.SubmitResultResponse} field-for-field. */
public record ResultResponse(long reviewId, String status) {
}
