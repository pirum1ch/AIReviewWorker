package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkerInfoService} — no Spring context needed.
 * Covers quantization inference, tok/s calculation, and public-status mapping.
 */
class WorkerInfoServiceTest {

    private WorkerProperties props;
    private WorkerStateHolder stateHolder;
    private WorkerInfoService service;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties("127.0.0.1", "8081", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("worker-1");
        props.getBackend().setId("backend-1");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        props.getLlama().setModel("some-model");
        stateHolder = new WorkerStateHolder();
        service = new WorkerInfoService(props, stateHolder);
    }

    // ---- quantization inference ----------------------------------------------------------------

    @Test
    void quantizationExtractedFromQ4KMInFilename() {
        String q = service.inferQuantization("/models/Qwen2.5-Coder-7B-Q4_K_M.gguf");
        assertThat(q).isEqualTo("Q4_K_M");
    }

    @Test
    void quantizationExtractedFromFP16InFilename() {
        String q = service.inferQuantization("/models/llama-3-8b-FP16.gguf");
        assertThat(q).isEqualTo("FP16");
    }

    @Test
    void quantizationNullWhenNoTokenInFilename() {
        String q = service.inferQuantization("/models/plain-model-name.gguf");
        assertThat(q).isNull();
    }

    @Test
    void quantizationNullWhenModelPathIsNull() {
        String q = service.inferQuantization(null);
        assertThat(q).isNull();
    }

    // ---- currentTokPerSec ----------------------------------------------------------------------

    @Test
    void tokPerSecNullWhenNoCompletionRecorded() {
        assertThat(stateHolder.currentTokPerSec()).isNull();
    }

    @Test
    void tokPerSecCalculatedCorrectly() {
        stateHolder.recordCompletion(1000, 10_000L);
        assertThat(stateHolder.currentTokPerSec()).isEqualTo(100.0);
    }

    @Test
    void recordCompletionIgnoresNullTokens() {
        stateHolder.recordCompletion(null, 10_000L);
        assertThat(stateHolder.currentTokPerSec()).isNull();
    }

    @Test
    void recordCompletionIgnoresNonPositiveDuration() {
        stateHolder.recordCompletion(500, 0L);
        assertThat(stateHolder.currentTokPerSec()).isNull();
    }

    // ---- public status mapping -----------------------------------------------------------------

    @Test
    void runningStatusMappedToBusy() {
        stateHolder.markRunning();
        assertThat(service.assembleInfoResponse().status()).isEqualTo("BUSY");
    }

    @Test
    void startingStatusMappedToAvailable() {
        // STARTING is the default initial state
        assertThat(service.assembleInfoResponse().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void idleStatusMappedToAvailable() {
        stateHolder.markIdle();
        assertThat(service.assembleInfoResponse().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void errorStatusMappedToError() {
        stateHolder.markError();
        assertThat(service.assembleInfoResponse().status()).isEqualTo("ERROR");
    }

    // ---- Q8_0 quantization (not just Q4_K_M) ---------------------------------------------------

    @Test
    void quantizationExtractedFromQ8_0InFilename() {
        String q = service.inferQuantization("/models/mistral-7b-Q8_0.gguf");
        assertThat(q).isEqualTo("Q8_0");
    }

    // ---- zero tokens edge case -----------------------------------------------------------------

    @Test
    void recordCompletionIgnoresZeroTokens() {
        stateHolder.recordCompletion(0, 5_000L);
        assertThat(stateHolder.currentTokPerSec()).isNull();
    }

    // ---- concurrent write-then-read atomicity --------------------------------------------------

    @Test
    void recordCompletionThenImmediateReadIsConsistent() throws InterruptedException {
        // Single write followed by a concurrent read must never produce a stale (null) result
        // after the write is confirmed. AtomicReference guarantees visibility.
        stateHolder.recordCompletion(2000, 4_000L);
        // Read from a separate thread to exercise cross-thread visibility.
        Double[] result = new Double[1];
        Thread reader = new Thread(() -> result[0] = stateHolder.currentTokPerSec());
        reader.start();
        reader.join(1_000);
        assertThat(result[0]).isEqualTo(500.0);
    }

    // ---- assembleInfoResponse field names (structural contract) --------------------------------

    @Test
    void assembleInfoResponseEngineIsLlamaCpp() {
        assertThat(service.assembleInfoResponse().engine()).isEqualTo("llama.cpp");
    }

    @Test
    void assembleInfoResponseModelMatchesProperty() {
        props.getLlama().setModel("/models/test.gguf");
        WorkerInfoService svc = new WorkerInfoService(props, stateHolder);
        assertThat(svc.assembleInfoResponse().model()).isEqualTo("/models/test.gguf");
    }

    // ---- assembleConfigResponse workDir derived from model parent ------------------------------

    @Test
    void assembleConfigResponseWorkDirIsParentOfModel() {
        Path modelPath = Path.of("/models/subdir/model.gguf");
        props.getLlama().setModel(modelPath.toString());
        WorkerInfoService svc = new WorkerInfoService(props, new WorkerStateHolder());
        String expectedWorkDir = modelPath.getParent().toString();
        assertThat(svc.assembleConfigResponse().workDir()).isEqualTo(expectedWorkDir);
    }

    @Test
    void assembleConfigResponseWorkDirFallsBackToDotWhenNoParent() {
        props.getLlama().setModel("model.gguf"); // no directory part → getParent() == null
        WorkerInfoService svc = new WorkerInfoService(props, new WorkerStateHolder());
        assertThat(svc.assembleConfigResponse().workDir()).isEqualTo(".");
    }

    @Test
    void assembleConfigResponseWorkDirFallsBackToDotWhenModelNull() {
        props.getLlama().setModel(null);
        WorkerInfoService svc = new WorkerInfoService(props, new WorkerStateHolder());
        assertThat(svc.assembleConfigResponse().workDir()).isEqualTo(".");
    }
}
