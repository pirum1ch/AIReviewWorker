package com.review.worker.gateway.dto;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.SubmitResultRequest} field-for-field.
 *
 * <p>{@code finishReason} (SRO-42/43): llama-server's {@code finish_reason} for this completion,
 * forwarded verbatim (untrusted, but small and low-risk — the Gateway whitelist-parses it, never
 * {@code Enum.valueOf}s the wire text, SRO-43). {@code null} for an old llama-server build that omits
 * the field (SRO-44).
 */
public record ResultRequest(
        String workerId,
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model,
        String finishReason) {

    /**
     * FW-05/WSR-10 hardening: the default record {@code toString()} would dump the full raw LLM response
     * into any accidental {@code log.debug("{}", request)}/exception-message rendering. This does not
     * affect JSON serialization, which Jackson performs via the accessors/canonical constructor, not
     * {@code toString()}.
     */
    @Override
    public String toString() {
        int rawResponseChars = rawResponse == null ? 0 : rawResponse.length();
        return "ResultRequest[workerId=" + workerId + ", rawResponse=<masked, " + rawResponseChars + " chars>, "
                + "promptTokens=" + promptTokens + ", completionTokens=" + completionTokens
                + ", durationMs=" + durationMs + ", model=" + model + ", finishReason=" + finishReason + "]";
    }
}
