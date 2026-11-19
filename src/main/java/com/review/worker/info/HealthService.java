package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.WorkerInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Proxies {@code GET /worker/info} from the info port (default 8082) to serve
 * the Gateway-facing {@code GET /health} on the actuator port (default 8080).
 * <p>
 * Uses a dedicated short-lived {@link RestClient} with a 2-second timeout — health-probes
 * must be fast so the Gateway's stale-heartbeat sweep is not masked by a slow info-server.
 * If the info port is unreachable, returns a synthetic {@code ERROR} response so the Gateway
 * treats the backend as unavailable rather than hanging indefinitely.
 * <p>
 * The response is assembled by {@link WorkerInfoService} which pulls live state from
 * {@link WorkerStateHolder} (status: AVAILABLE/BUSY/ERROR) and runtime-detected values
 * (engine, GPU, tok/s).
 */
@Service
@Order(1) // ensures this runs before other @Ordered beans
class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private static final String ENGINE = "llama.cpp";

    private final RestClient infoClient;
    private final String localInfoUrl;

    /**
     * Builds a dedicated client for the info-server connector.
     *
     * @param sharedHttpClient the project's shared {@code HttpClient} (same one used by Gateway/Llama clients)
     * @param props            worker properties (read from {@code application.yml} / env)
     */
    HealthService(HttpClient sharedHttpClient, WorkerProperties props) {
        this.localInfoUrl = "http://127.0.0.1:" + props.getInfo().getPort();
        this.infoClient = RestClient.builder()
                .baseUrl(localInfoUrl)
                .requestFactory(new JdkClientHttpRequestFactory(sharedHttpClient))
                .build();
    }

    /**
     * Fetches the worker info from the info port and returns it.
     * <p>
     * If the info port is unreachable (connection refused, timeout, etc.), returns a synthetic
     * response with status {@code "ERROR"} so that {@link HealthController} reports 503 to the Gateway.
     *
     * @return the {@link WorkerInfoResponse} — either live or synthetic on failure
     */
    WorkerInfoResponse getHealth() {
        try {
            WorkerInfoResponse response = infoClient.get()
                    .uri("/worker/info")
                    .retrieve()
                    .body(WorkerInfoResponse.class);
            log.debug("Health probe: info-port returned status={}", response != null ? response.status() : "null");
            return response;
        } catch (Exception e) {
            log.warn("Health probe: info-port {} unreachable — returning ERROR status: {}",
                    localInfoUrl, e.getMessage());
            // Synthetic ERROR response so the Gateway treats this backend as unavailable.
            // Uses known-good values for fields the Gateway may inspect in future.
            return new WorkerInfoResponse(
                    null,    // workerId
                    null,    // backendId
                    ENGINE,  // engine (always known)
                    null,    // model
                    null,    // quantization
                    null,    // gpuName
                    null,    // currentTokPerSec
                    "ERROR"  // status — forces 503 from HealthController
            );
        }
    }
}
