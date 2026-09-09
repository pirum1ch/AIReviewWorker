package com.review.worker.llama;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Structured Review Output (architecture §3.3, SRO-13): the Gateway-supplied decoder constraint,
 * already parsed and defensively validated by {@link DecoderConstraintResolver}, ready to attach
 * verbatim to a {@link com.review.worker.llama.dto.ChatCompletionRequest}. At most one field is ever
 * non-null — this is a sealed two-shape result, mirroring the Gateway's own {@code
 * DecoderConstraintRenderer.DecoderConstraint}.
 */
public record DecoderConstraint(JsonNode responseFormat, JsonNode jsonSchema) {

    public static final DecoderConstraint NONE = new DecoderConstraint(null, null);
}
