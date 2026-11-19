package com.review.worker.info;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link WorkerInfoController} — standalone MockMvc, no Spring context.
 * Verifies JSON field names, types, and status mapping for {@code GET /worker/info}.
 */
class WorkerInfoControllerTest {

    private WorkerStateHolder stateHolder;
    private WorkerInfoService infoService;
    private MockMvc mvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        WorkerProperties props = new WorkerProperties("127.0.0.1", "8081", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("worker-42");
        props.getBackend().setId("backend-99");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        props.getLlama().setModel("/models/mistral-Q4_K_M.gguf");

        stateHolder = new WorkerStateHolder();
        infoService = new WorkerInfoService(props, stateHolder);
        mvc = MockMvcBuilders.standaloneSetup(new WorkerInfoController(infoService)).build();
    }

    @Test
    void getInfoReturns200() throws Exception {
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void getInfoReturnsExpectedFieldNames() throws Exception {
        String body = mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
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
    void getInfoStatusIsAvailableByDefault() throws Exception {
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void getInfoStatusIsBusyWhenRunning() throws Exception {
        stateHolder.markRunning();
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("BUSY"));
    }

    @Test
    void getInfoStatusIsErrorWhenMarkError() throws Exception {
        stateHolder.markError();
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ERROR"));
    }

    @Test
    void getInfoEngineIsLlamaCpp() throws Exception {
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.engine").value("llama.cpp"));
    }

    @Test
    void getInfoWorkerIdAndBackendIdPresent() throws Exception {
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workerId").value("worker-42"))
                .andExpect(jsonPath("$.backendId").value("backend-99"));
    }

    @Test
    void getInfoQuantizationInferredFromModel() throws Exception {
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.quantization").value("Q4_K_M"));
    }

    @Test
    void getInfoCurrentTokPerSecIsNullJsonWhenNoCompletion() throws Exception {
        String body = mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = mapper.readTree(body);
        // currentTokPerSec must be present and null (not absent) when no completion recorded
        assertThat(node.has("currentTokPerSec")).isTrue();
        assertThat(node.get("currentTokPerSec").isNull()).isTrue();
    }

    @Test
    void getInfoCurrentTokPerSecIsNumericAfterCompletion() throws Exception {
        stateHolder.recordCompletion(300, 3_000L);
        mvc.perform(get("/worker/info").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.currentTokPerSec").value(100.0));
    }
}
