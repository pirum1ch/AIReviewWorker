package com.review.worker.info;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.WorkerInfoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link HealthController} — standalone MockMvc, no Spring context.
 * Verifies HTTP status code mapping and response body for {@code GET /health}.
 * <p>
 * The {@link HealthService} is mocked so the test focuses on the controller's
 * status-code logic without depending on a running info-server.
 */
class HealthControllerTest {

    private HealthService healthService;
    private MockMvc mvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        WorkerProperties props = new WorkerProperties("127.0.0.1", "8080", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("worker-42");
        props.getBackend().setId("backend-99");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        props.getLlama().setModel("/models/mistral-Q4_K_M.gguf");

        WorkerStateHolder stateHolder = new WorkerStateHolder();
        WorkerInfoService infoService = new WorkerInfoService(props, stateHolder);

        // Mock the HealthService — controller tests focus on status-code mapping
        healthService = mock(HealthService.class);

        mvc = MockMvcBuilders.standaloneSetup(new HealthController(healthService)).build();
    }

    @Test
    void healthReturns200WhenAvailable() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse("w", "b", "llama.cpp", "model.gguf", "Q4", "GPU", 100.0, "AVAILABLE"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void healthReturns503WhenBusy() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse("w", "b", "llama.cpp", "model.gguf", null, null, null, "BUSY"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void healthReturns503WhenError() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse(null, null, "llama.cpp", null, null, null, null, "ERROR"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void healthBodyContainsAllExpectedFields() throws Exception {
        WorkerInfoResponse expected = new WorkerInfoResponse(
                "worker-42", "backend-99", "llama.cpp",
                "/models/mistral-Q4_K_M.gguf", "Q4_K_M", "GPU", 100.0, "AVAILABLE");
        when(healthService.getHealth()).thenReturn(expected);

        String body = mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = mapper.readTree(body);
        assertThat(node.has("workerId")).isTrue();
        assertThat(node.has("backendId")).isTrue();
        assertThat(node.has("engine")).isTrue();
        assertThat(node.has("model")).isTrue();
        assertThat(node.has("quantization")).isTrue();
        assertThat(node.has("gpuName")).isTrue();
        assertThat(node.has("currentTokPerSec")).isTrue();
        assertThat(node.has("status")).isTrue();
    }

    @Test
    void healthEngineIsLlamaCpp() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse(null, null, "llama.cpp", null, null, null, null, "AVAILABLE"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.engine").value("llama.cpp"));
    }

    @Test
    void healthWorkerIdAndBackendIdPresent() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse("worker-42", "backend-99", null, null, null, null, null, "AVAILABLE"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workerId").value("worker-42"))
                .andExpect(jsonPath("$.backendId").value("backend-99"));
    }

    @Test
    void healthStatusAvailableMapsTo200() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse(null, null, null, null, null, null, null, "AVAILABLE"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void healthStatusBusyMapsTo503() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse(null, null, null, null, null, null, null, "BUSY"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("BUSY"));
    }

    @Test
    void healthStatusErrorMapsTo503() throws Exception {
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse(null, null, null, null, null, null, null, "ERROR"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"));
    }

    @Test
    void healthStatusStartingMapsTo503() throws Exception {
        // STARTING is not a public status — but if it ever leaks through,
        // the controller treats it as 503 (only AVAILABLE → 200).
        when(healthService.getHealth()).thenReturn(
                new WorkerInfoResponse(null, null, null, null, null, null, null, "STARTING"));
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());
    }
}
