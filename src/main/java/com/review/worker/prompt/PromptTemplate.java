package com.review.worker.prompt;

/**
 * Parsed shape of a {@code prompts/<version>.yml} file. {@code model}/{@code temperature}/
 * {@code maxTokens} are optional per-template overrides (null means "fall back to {@code llama.*}
 * config" — see {@link PromptTemplateService}).
 */
public record PromptTemplate(String system, String user, String model, Double temperature, Integer maxTokens) {
}
