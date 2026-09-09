package com.review.worker.gateway;

import com.review.worker.gateway.dto.ResultResponse;

/**
 * Typed result of {@link GatewayClient#submitResult(long, com.review.worker.gateway.dto.ResultRequest)}.
 * See {@link HeartbeatOutcome} for why 403/404 are represented as data rather than exceptions.
 */
public record ResultOutcome(ResultStatus status, ResultResponse response) {

    public static ResultOutcome accepted(ResultResponse response) {
        return new ResultOutcome(ResultStatus.ACCEPTED, response);
    }

    public static ResultOutcome notFound() {
        return new ResultOutcome(ResultStatus.NOT_FOUND, null);
    }

    public static ResultOutcome forbidden() {
        return new ResultOutcome(ResultStatus.FORBIDDEN, null);
    }

    public enum ResultStatus {
        ACCEPTED,
        NOT_FOUND,
        FORBIDDEN
    }
}
