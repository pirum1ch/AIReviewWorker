package com.review.worker.info;

import tools.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link HealthService}.
 * <p>
 * Uses {@code okhttp3:mockwebserver} — the same fake-socket approach as the rest of the test suite.
 * Verifies live proxying from the info port and the ERROR fallback when unreachable.
 */
class HealthServiceTest {

    private MockWebServer infoServer;
    private WorkerProperties props;
    private HealthService healthService;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        infoServer = new MockWebServer();
        infoServer.start();

        props = new WorkerProperties("127.0.0.1", "8080", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getInfo().setPort(infoServer.getPort());

        healthService = new HealthService(java.net.http.HttpClient.newHttpClient(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        infoServer.shutdown();
    }

    @Test
    void getHealthReturnsInfoWhenInfoPortReachable() throws Exception {
        // Queue a valid WorkerInfoResponse on the mock server
        String responseBody = """
                {
                    "workerId": "worker-1",
                    "backendId": "backend-1",
                    "engine": "llama.cpp",
                    "model": "/models/llama-3-8b-Q4_K_M.gguf",
                    "quantization": "Q4_K_M",
                    "gpuName": "NVIDIA GeForce RTX 4090",
                    "currentTokPerSec": 150.5,
                    "status": "AVAILABLE"
                }
                """;
        infoServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json"));

        var response = healthService.getHealth();

        assertThat(response.workerId()).isEqualTo("worker-1");
        assertThat(response.backendId()).isEqualTo("backend-1");
        assertThat(response.engine()).isEqualTo("llama.cpp");
        assertThat(response.status()).isEqualTo("AVAILABLE");
        assertThat(response.currentTokPerSec()).isEqualTo(150.5);
    }

    @Test
    void getHealthReturnsBusStatus() throws Exception {
        infoServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "workerId": "w1",
                            "backendId": "b1",
                            "engine": "llama.cpp",
                            "model": "model.gguf",
                            "quantization": null,
                            "gpuName": null,
                            "currentTokPerSec": null,
                            "status": "BUSY"
                        }
                        """));

        var response = healthService.getHealth();
        assertThat(response.status()).isEqualTo("BUSY");
    }

    @Test
    void getHealthReturnsErrorStatus() throws Exception {
        infoServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "workerId": "w1",
                            "backendId": "b1",
                            "engine": "llama.cpp",
                            "model": "model.gguf",
                            "quantization": null,
                            "gpuName": null,
                            "currentTokPerSec": null,
                            "status": "ERROR"
                        }
                        """));

        var response = healthService.getHealth();
        assertThat(response.status()).isEqualTo("ERROR");
    }

    @Test
    void getHealthReturnsSyntheticErrorWhenInfoPortUnreachable() throws Exception {
        // Shut down the mock server — the service should handle this gracefully
        infoServer.shutdown();

        var response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.engine()).isEqualTo("llama.cpp");
    }

    @Test
    void getHealthReturnsSyntheticErrorOnConnectionRefused() throws Exception {
        // Create a new service pointing to a port that's definitely not listening
        WorkerProperties unreachableProps = new WorkerProperties("127.0.0.1", "8080", "", "");
        unreachableProps.getGateway().setUrl("https://gateway.internal");
        unreachableProps.getGateway().setApiKey("a".repeat(40));
        // Port 1 is reserved and should not be listening
        unreachableProps.getInfo().setPort(1);

        HealthService unreachableService = new HealthService(
                java.net.http.HttpClient.newHttpClient(), unreachableProps);

        var response = unreachableService.getHealth();

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.engine()).isEqualTo("llama.cpp");
    }
}
