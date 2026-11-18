package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.WorkerConfigResponse;
import com.review.worker.info.dto.WorkerInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles the REQ-6 ({@code GET /worker/info}) and REQ-7 ({@code GET /worker/config}) responses
 * from live state and runtime-detected values.
 *
 * <p>Hostname and GPU name are resolved once at construction and cached — both are expensive to
 * re-detect and do not change while the process is alive.
 */
@Service
public class WorkerInfoService {

    private static final Logger log = LoggerFactory.getLogger(WorkerInfoService.class);

    // Engine is fixed: this Worker only speaks to llama-server (llama.cpp).
    private static final String ENGINE = "llama.cpp";

    /**
     * Case-insensitive: matches Q4_K_M, Q8_0, FP16, BF16, INT4, INT8 and common llama.cpp
     * quantization suffixes embedded anywhere in the model filename.
     */
    private static final Pattern QUANT_PATTERN = Pattern.compile(
            "(?i)(?:^|[_.-])(q[0-9][a-z0-9_]*|fp16|bf16|q8_0|int8|int4)(?:[_.-]|$)");

    private final WorkerProperties props;
    private final WorkerStateHolder stateHolder;
    private final Instant startTime = Instant.now();
    private final String hostname;
    private final String gpuName;

    public WorkerInfoService(WorkerProperties props, WorkerStateHolder stateHolder) {
        this.props = props;
        this.stateHolder = stateHolder;
        this.hostname = detectHostname();
        this.gpuName = detectGpuName();
    }

    private static String detectHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /** Tries {@code nvidia-smi} once at startup; returns {@code null} if unavailable or not a GPU node. */
    private static String detectGpuName() {
        try {
            Process p = new ProcessBuilder(
                    "nvidia-smi", "--query-gpu=gpu_name", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();
            // waitFor before readAllBytes: process exits first (small output fits in OS pipe buffer),
            // then we read the already-buffered bytes — avoids blocking readAllBytes on a chatty process.
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return out.isEmpty() ? null : out.lines().findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("nvidia-smi not available — gpuName will be null: {}", e.getMessage());
        }
        return null;
    }

    /** Infers quantization tag from the model filename (basename only), or {@code null} if none found. */
    String inferQuantization(String modelPath) {
        if (modelPath == null) {
            return null;
        }
        String name = Paths.get(modelPath).getFileName().toString();
        Matcher m = QUANT_PATTERN.matcher(name);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** Maps internal status to the coarsened public vocabulary. */
    private String publicStatus(WorkerStateHolder.Status s) {
        return switch (s) {
            case RUNNING -> "BUSY";
            case ERROR   -> "ERROR";
            default      -> "AVAILABLE";
        };
    }

    /** Builds the unauthenticated public response ({@code GET /worker/info}). */
    public WorkerInfoResponse assembleInfoResponse() {
        WorkerStateHolder.Status s = stateHolder.getStatus();
        String model = props.getLlama().getModel();
        return new WorkerInfoResponse(
                props.getWorker().getId(),
                props.getBackend().getId(),
                ENGINE,
                model,
                inferQuantization(model),
                gpuName,
                stateHolder.currentTokPerSec(),
                publicStatus(s)
        );
    }

    /** Builds the admin-only full-detail response ({@code GET /worker/config}). */
    public WorkerConfigResponse assembleConfigResponse() {
        WorkerStateHolder.Status s = stateHolder.getStatus();
        String model = props.getLlama().getModel();
        String workDir = model != null
                ? (Paths.get(model).getParent() != null ? Paths.get(model).getParent().toString() : ".")
                : ".";
        return new WorkerConfigResponse(
                props.getWorker().getId(),
                props.getBackend().getId(),
                ENGINE,
                model,
                inferQuantization(model),
                hostname,
                gpuName,
                stateHolder.currentTokPerSec(),
                s.name(),
                Duration.between(startTime, Instant.now()).getSeconds(),
                workDir,
                props.getLlama().getUrl(),
                props.getNetwork().getPollIntervalMs()
        );
    }
}
