package com.review.worker.gateway;

import com.review.worker.error.GatewayUnavailableException;
import com.review.worker.gateway.dto.ClaimRequest;
import com.review.worker.gateway.dto.ClaimResponse;
import com.review.worker.gateway.dto.FailRequest;
import com.review.worker.gateway.dto.HeartbeatRequest;
import com.review.worker.gateway.dto.HeartbeatResponse;
import com.review.worker.gateway.dto.ResultRequest;
import com.review.worker.gateway.dto.ResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * The Worker's sole channel to the Review Gateway: claim a job, heartbeat it, submit its result. Never
 * talks to GitLab or PostgreSQL directly (architecture's core invariant); never logs the bearer token,
 * the diff, or the raw LLM response (WSR-10) — only ids, statuses, and sizes.
 */
@Component
public class GatewayClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    private final RestClient gatewayRestClient;

    public GatewayClient(@Qualifier("gatewayRestClient") RestClient gatewayRestClient) {
        this.gatewayRestClient = gatewayRestClient;
    }

    /**
     * {@code POST /jobs/claim}. Returns {@link Optional#empty()} on {@code 204} (nothing to claim) — a
     * 204 never carries a body, so the message-converter chain yields {@code null} rather than raising
     * an exception; no separate status-code branch is needed for it.
     *
     * @throws GatewayUnavailableException on a connection failure or a 5xx from the Gateway.
     */
    public Optional<ClaimResponse> claim(String backendId, String workerId) {
        try {
            ClaimResponse response = gatewayRestClient.post()
                    .uri("/jobs/claim")
                    .body(new ClaimRequest(backendId, workerId))
                    .retrieve()
                    .body(ClaimResponse.class);
            // WOC-01/WOC-06: INFO only on an actual claim; the far more frequent empty (204) case drops
            // to DEBUG so a busy Worker isn't drowned out by an idle one polling every few seconds.
            if (response != null) {
                log.info("Claimed job (jobId={}, reviewId={})", response.jobId(), response.reviewId());
            } else {
                log.debug("Claim poll: no job available");
            }
            return Optional.ofNullable(response);
        } catch (RestClientResponseException e) {
            throw mapServerError("claim", e);
        } catch (ResourceAccessException e) {
            throw new GatewayUnavailableException("Gateway unreachable while claiming a job", e);
        }
    }

    /**
     * {@code POST /jobs/{id}/heartbeat}.
     *
     * @throws GatewayUnavailableException on a connection failure or a 5xx from the Gateway.
     */
    public HeartbeatOutcome heartbeat(long jobId, String workerId) {
        try {
            HeartbeatResponse response = gatewayRestClient.post()
                    .uri("/jobs/{id}/heartbeat", jobId)
                    .body(new HeartbeatRequest(workerId))
                    .retrieve()
                    .body(HeartbeatResponse.class);
            boolean shouldContinue = response != null && response.shouldContinue();
            log.debug("Heartbeat sent (jobId={}, shouldContinue={})", jobId, shouldContinue);
            return HeartbeatOutcome.accepted(shouldContinue);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.info("Heartbeat rejected: job not found (jobId={})", jobId);
                return HeartbeatOutcome.notFound();
            }
            if (e.getStatusCode().value() == 403) {
                log.info("Heartbeat rejected: not job owner (jobId={})", jobId);
                return HeartbeatOutcome.forbidden();
            }
            throw mapServerError("heartbeat", e);
        } catch (ResourceAccessException e) {
            throw new GatewayUnavailableException("Gateway unreachable while sending heartbeat (jobId=" + jobId + ")", e);
        }
    }

    /**
     * {@code POST /jobs/{id}/result}. Idempotent on the Gateway side if the job is no longer {@code RUNNING}.
     *
     * @throws GatewayUnavailableException on a connection failure or a 5xx from the Gateway.
     */
    public ResultOutcome submitResult(long jobId, ResultRequest request) {
        try {
            ResultResponse response = gatewayRestClient.post()
                    .uri("/jobs/{id}/result", jobId)
                    .body(request)
                    .retrieve()
                    .body(ResultResponse.class);
            log.info("Result submitted (jobId={}, status={})", jobId, response == null ? "unknown" : response.status());
            return ResultOutcome.accepted(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.info("Result submission rejected: job not found (jobId={})", jobId);
                return ResultOutcome.notFound();
            }
            if (e.getStatusCode().value() == 403) {
                log.info("Result submission rejected: not job owner (jobId={})", jobId);
                return ResultOutcome.forbidden();
            }
            throw mapServerError("submitResult", e);
        } catch (ResourceAccessException e) {
            throw new GatewayUnavailableException("Gateway unreachable while submitting result (jobId=" + jobId + ")", e);
        }
    }

    /**
     * {@code POST /jobs/{id}/fail} (architecture §5, WOC-35): single best-effort attempt, no retry/
     * backoff of its own — the caller ({@code WorkerLoop}) is expected to catch {@link
     * GatewayUnavailableException}, log a WARN, count it, and move on. A non-2xx/unreachable outcome
     * (including a {@code 404} from an older Gateway build that has no such endpoint) is exactly as safe
     * to swallow as any other: this endpoint is a pure latency optimization and the Gateway's own
     * stale-heartbeat sweep is the correctness backstop (WOC-36).
     *
     * @throws GatewayUnavailableException on any non-2xx response or a connection failure.
     */
    public void reportFailure(long jobId, FailRequest request) {
        try {
            gatewayRestClient.post()
                    .uri("/jobs/{id}/fail", jobId)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Failure reported (jobId={}, reason={})", jobId, request.reason());
        } catch (RestClientResponseException e) {
            throw mapServerError("reportFailure", e);
        } catch (ResourceAccessException e) {
            throw new GatewayUnavailableException("Gateway unreachable while reporting failure (jobId=" + jobId + ")", e);
        }
    }

    private GatewayUnavailableException mapServerError(String operation, RestClientResponseException e) {
        return new GatewayUnavailableException(
                "Gateway returned " + e.getStatusCode().value() + " during " + operation, e);
    }
}
