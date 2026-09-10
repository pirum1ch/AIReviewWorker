package com.review.worker.gateway.dto;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.AnnounceBackendResponse} field-for-field
 * (Backend Self-Registration, architecture §4.2/BSR-02). {@code status} is the row's *effective* status
 * after the announce (e.g. {@code ACTIVE}/{@code MAINTENANCE}/{@code OFFLINE}) — announce never
 * resurrects a parked backend, so a non-{@code ACTIVE} value here means an operator parked it, not that
 * this call failed. {@code created} is {@code true} for a fresh INSERT, {@code false} for an update/no-op.
 */
public record AnnounceResponse(String name, String status, boolean created) {
}
