package com.review.worker.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.ClaimJobResponse} field-for-field.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} (PMT-05/PMR-24): see {@link JobPayload}'s
 * javadoc — forward compatibility across independent Gateway/Worker deploys is a stated contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimResponse(long jobId, long reviewId, JobPayload payload) {

    /**
     * FW-05/WSR-10 hardening: explicit override (rather than relying solely on {@link JobPayload}'s own
     * masked {@code toString()}) so the no-raw-diff-in-logs invariant is documented and holds at this
     * type too, independent of how {@code payload} is rendered.
     */
    @Override
    public String toString() {
        return "ClaimResponse[jobId=" + jobId + ", reviewId=" + reviewId + ", payload=" + payload + "]";
    }
}
