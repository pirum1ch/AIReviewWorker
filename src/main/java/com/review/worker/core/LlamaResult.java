package com.review.worker.core;

/**
 * Outcome of a successful llama-server chat completion, ready to be forwarded to the Gateway as a
 * {@code ResultRequest} by {@link WorkerLoop}.
 *
 * <p>Lives in {@code core/} per architecture §11's Branch-2 package inventory (moved here from
 * {@code llama/}, where it was deliberately parked during Branch 1 because {@code core/} was out of that
 * branch's scope; its shape is unchanged).
 */
public record LlamaResult(
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        long durationMs,
        String model,
        String finishReason) {
}
