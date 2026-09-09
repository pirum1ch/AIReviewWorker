package com.review.worker.llama.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of {@code choices[]} in a llama-server chat-completion response; unknown fields ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Choice(ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
}
