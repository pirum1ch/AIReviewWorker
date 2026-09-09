package com.review.worker.llama.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** {@code POST /v1/chat/completions} response body (OpenAI-compatible llama-server API). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(String id, List<Choice> choices, Usage usage) {
}
