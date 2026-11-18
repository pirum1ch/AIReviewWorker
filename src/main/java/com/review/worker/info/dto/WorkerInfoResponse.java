package com.review.worker.info.dto;

/**
 * Unauthenticated public view — coarsened per security review.
 * Status is mapped to {@code "AVAILABLE"} / {@code "BUSY"} / {@code "ERROR"}: the internal
 * {@code STARTING} state is not exposed (maps to {@code "AVAILABLE"}).
 * Node ID and uptime are intentionally absent from this response.
 */
public record WorkerInfoResponse(
        String workerId,
        String backendId,
        String engine,
        String model,
        String quantization,
        String gpuName,
        Double currentTokPerSec,
        String status  // "AVAILABLE" | "BUSY" | "ERROR"
) {}
