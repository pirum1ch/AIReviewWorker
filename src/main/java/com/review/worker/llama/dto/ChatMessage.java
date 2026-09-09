package com.review.worker.llama.dto;

/**
 * OpenAI Chat Completions {@code {role, content}} shape — reused both for outbound request messages and
 * for the inbound {@code choices[0].message} field, since both use the identical structure.
 */
public record ChatMessage(String role, String content) {
}
