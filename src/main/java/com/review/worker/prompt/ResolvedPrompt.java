package com.review.worker.prompt;

import com.review.worker.llama.dto.ChatMessage;

import java.util.List;

/**
 * The fully-resolved bundle {@link PromptTemplateService#resolve} hands to {@code LlamaClient}: the
 * chat messages (system + user, diff already substituted) and the effective model/temperature/maxTokens
 * (template override if present, else the {@code llama.*} config default).
 */
public record ResolvedPrompt(List<ChatMessage> messages, String model, double temperature, int maxTokens) {
}
