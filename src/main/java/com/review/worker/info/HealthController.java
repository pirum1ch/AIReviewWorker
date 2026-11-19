package com.review.worker.info;

import com.review.worker.info.dto.WorkerInfoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway-facing health-probe endpoint ({@code GET /health}).
 * <p>
 * Probed by the Gateway on the actuator port (default 8080). The Gateway uses only the HTTP
 * status code as its decision criterion:
 * <ul>
 *   <li>{@code 200 OK} — status is {@code AVAILABLE} → {@code applySuccess()}</li>
 *   <li>{@code 503 Service Unavailable} — status is {@code BUSY} or {@code ERROR} →
 *   {@code BackendUnavailableException} → {@code applyFailure()}</li>
 * </ul>
 * <p>
 * The response body carries the full {@link WorkerInfoResponse} (engine, model, quantization,
 * gpuName, tok/s, status) for operational visibility — the Gateway may use it in the future.
 *
 * @see WorkerInfoService#assembleInfoResponse()
 */
@RestController
class HealthController {

    private final HealthService healthService;

    HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<WorkerInfoResponse> health() {
        WorkerInfoResponse info = healthService.getHealth();
        HttpStatus status = "AVAILABLE".equals(info.status())
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(info);
    }
}
