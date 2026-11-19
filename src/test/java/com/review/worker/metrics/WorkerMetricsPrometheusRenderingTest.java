package com.review.worker.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
        "management.server.address=127.0.0.1",
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,prometheus"
})
class WorkerMetricsPrometheusRenderingTest {

    @LocalManagementPort
    private int managementPort;

    @org.springframework.beans.factory.annotation.Autowired
    private WorkerMetrics workerMetrics;

    @Test
    void allSixWorkerMetricsRenderWithExactPrometheusNames() throws Exception {
        workerMetrics.incrementJobsTotal();
        workerMetrics.incrementJobsCompleted();
        workerMetrics.incrementJobsFailed();
        workerMetrics.incrementGatewayErrors();
        workerMetrics.recordLlamaDuration(java.time.Duration.ofMillis(500));

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(200, 299);
        String body = response.body();
        assertThat(body).contains("worker_jobs_total");
        assertThat(body).contains("worker_jobs_completed_total");
        assertThat(body).contains("worker_jobs_failed_total");
        assertThat(body).contains("worker_llama_duration_seconds_count");
        assertThat(body).contains("worker_llama_duration_seconds_sum");
        assertThat(body).contains("worker_gateway_errors_total");
        assertThat(body).contains("worker_uptime_seconds");
    }
}
