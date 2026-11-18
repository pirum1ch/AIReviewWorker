package com.review.worker.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full Worker application context and asserts that all six metrics from architecture §8 render
 * under their exact Prometheus names on {@code /actuator/prometheus}. Micrometer's naming rules (dots to
 * underscores, Counters auto-suffixed {@code _total}, this Timer's explicit seconds base unit rendering
 * {@code _seconds_*}, and the Gauge's explicit {@code baseUnit("seconds")} rendering {@code _seconds})
 * are exercised for real here rather than assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.url=https://gateway.internal",
        "gateway.api-key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "worker.id=worker-test",
        "backend.id=backend-test",
        "llama.url=http://127.0.0.1:8000",
        "llama.model=test-model",
        // Use the same server port for actuator (management.server.port not set = main port)
        "management.endpoints.web.exposure.include=health,prometheus"
})
class WorkerMetricsPrometheusRenderingTest {

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private WorkerMetrics workerMetrics;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void allSixWorkerMetricsRenderWithExactPrometheusNames() {
        workerMetrics.incrementJobsTotal();
        workerMetrics.incrementJobsCompleted();
        workerMetrics.incrementJobsFailed();
        workerMetrics.incrementGatewayErrors();
        workerMetrics.recordLlamaDuration(java.time.Duration.ofMillis(500));

        ResponseEntity<String> response = restClient.get()
                .uri("/actuator/prometheus")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String body = response.getBody();
        assertThat(body).contains("worker_jobs_total");
        assertThat(body).contains("worker_jobs_completed_total");
        assertThat(body).contains("worker_jobs_failed_total");
        assertThat(body).contains("worker_llama_duration_seconds_count");
        assertThat(body).contains("worker_llama_duration_seconds_sum");
        assertThat(body).contains("worker_gateway_errors_total");
        assertThat(body).contains("worker_uptime_seconds");
    }
}
