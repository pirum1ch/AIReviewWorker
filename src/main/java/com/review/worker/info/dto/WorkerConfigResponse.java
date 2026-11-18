package com.review.worker.info.dto;

/**
 * Admin-only view — full detail, gated by {@code WORKER_ADMIN_TOKEN} on the info endpoints.
 * The internal status string uses the enum name verbatim: {@code STARTING/IDLE/RUNNING/ERROR}.
 */
public record WorkerConfigResponse(
        String workerId,
        String backendId,
        String engine,
        String model,
        String quantization,
        String nodeId,
        String gpuName,
        Double currentTokPerSec,
        String status,
        long uptimeSeconds,
        String workDir,
        String llamaServerUrl,
        long pollIntervalMs
) {}
