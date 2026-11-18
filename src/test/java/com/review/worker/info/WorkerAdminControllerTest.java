package com.review.worker.info;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.WorkerConfigResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link WorkerAdminController} — standalone MockMvc, no Spring context needed.
 * Covers token validation, 503 when no token configured, and model-list delegation.
 */
class WorkerAdminControllerTest {

    private static final String GOOD_TOKEN = "a".repeat(40); // >=32 chars

    private WorkerProperties props;
    private WorkerInfoService infoService;
    private WorkerConfigService configService;
    private WorkerAdminController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties("127.0.0.1", "8081", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("worker-1");
        props.getBackend().setId("backend-1");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        props.getLlama().setModel("/models/test-Q4_K_M.gguf");
        props.getInfo().setAdminToken(GOOD_TOKEN);

        WorkerStateHolder stateHolder = new WorkerStateHolder();
        infoService = new WorkerInfoService(props, stateHolder);
        configService = new WorkerConfigService(props);
        controller = new WorkerAdminController(infoService, configService, props);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getConfigWithoutAuthorizationHeaderReturns403() throws Exception {
        mvc.perform(get("/worker/config"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getConfigWithWrongTokenReturns403() throws Exception {
        mvc.perform(get("/worker/config")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getConfigWithCorrectTokenReturns200AndBody() throws Exception {
        String body = mvc.perform(get("/worker/config")
                        .header("Authorization", "Bearer " + GOOD_TOKEN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        WorkerConfigResponse response = new ObjectMapper().readValue(body, WorkerConfigResponse.class);
        assertThat(response.workerId()).isEqualTo("worker-1");
        assertThat(response.backendId()).isEqualTo("backend-1");
        assertThat(response.model()).isEqualTo("/models/test-Q4_K_M.gguf");
        assertThat(response.status()).isEqualTo("STARTING");
    }

    @Test
    void getConfigWhenAdminTokenNotConfiguredReturns503() throws Exception {
        props.getInfo().setAdminToken(null);
        mvc.perform(get("/worker/config")
                        .header("Authorization", "Bearer " + GOOD_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ADMIN_DISABLED"));
    }

    @Test
    void getConfigWorkDirDerivedFromModelParent() throws Exception {
        // model path is /models/test-Q4_K_M.gguf → workDir must equal the parent dir
        // Use Path.of so the expected value is platform-neutral (\ on Windows, / on Unix).
        String expectedWorkDir = Path.of("/models/test-Q4_K_M.gguf").getParent().toString();
        mvc.perform(get("/worker/config")
                        .header("Authorization", "Bearer " + GOOD_TOKEN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workDir").value(expectedWorkDir));
    }

    @Test
    void getModelsWithCorrectTokenAndNoModelsDirReturns500() throws Exception {
        // modelsDir is null by default — listModels() throws IOException
        mvc.perform(get("/worker/models")
                        .header("Authorization", "Bearer " + GOOD_TOKEN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("IO_ERROR"));
    }
}
