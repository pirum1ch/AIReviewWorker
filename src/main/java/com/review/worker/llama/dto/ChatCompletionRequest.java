package com.review.worker.llama.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code POST /v1/chat/completions} request body (OpenAI-compatible llama-server API).
 *
 * <p>Structured Review Output (architecture §3.3/§9.3.6, threat model SOR-07 CRITICAL):
 * {@code responseFormat}/{@code jsonSchema} are the Gateway-computed decoder constraint, forwarded
 * <b>verbatim</b> — {@code @JsonInclude(NON_NULL)} means today's request body is byte-for-byte unchanged
 * whenever both are {@code null} (backend {@code OFF}, kill switch, or a non-structured
 * {@code promptVersion}). Deliberately <b>typed {@code JsonNode} fields</b>, never a generic
 * {@code Map<String, Object>} request-body overlay (§9.3.6's rejected alternative) — this is what
 * bounds a compromised/buggy Gateway to influencing only output <i>shape</i> via these two fixed wire
 * destinations, never arbitrary llama.cpp sampling parameters.
 *
 * <p>{@code chatTemplateKwargs} is the opposite kind of field: never Gateway-supplied, always computed by
 * {@code LlamaClient} from the Worker's own {@code worker.llama.enable-thinking} config
 * ({@code null} unless the operator explicitly opts out of thinking mode — see that property's javadoc).
 * A plain {@code JsonNode} is fine here precisely because the source is trusted local config, not an
 * external caller — unlike {@code responseFormat}/{@code jsonSchema}, there is no "compromised Gateway"
 * threat model to bound.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("response_format") JsonNode responseFormat,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("json_schema") JsonNode jsonSchema,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("chat_template_kwargs") JsonNode chatTemplateKwargs) {

    /** Legacy shape (no decoder constraint, no chat_template_kwargs) — existing test call sites. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature, int maxTokens) {
        this(model, messages, temperature, maxTokens, null, null, null);
    }
}
