package com.review.worker.llama.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structured Review Output (architecture §3.3, SRO-06/SOR-07): {@link ChatCompletionRequest}'s new
 * {@code responseFormat}/{@code jsonSchema} fields must not appear in the serialized body at all when
 * both are {@code null} -- the exact byte-for-byte-unchanged guarantee the design relies on for the
 * {@code OFF}/kill-switch/non-structured-version paths.
 */
class ChatCompletionRequestSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyFourFieldConstructorOmitsBothNewFieldsFromTheSerializedBody() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest("model-x", List.of(new ChatMessage("user", "hi")), 0.2, 100);

        String json = mapper.writeValueAsString(request);
        JsonNode root = mapper.readTree(json);

        assertThat(root.has("response_format")).isFalse();
        assertThat(root.has("json_schema")).isFalse();
        assertThat(root.get("model").asText()).isEqualTo("model-x");
        assertThat(root.get("max_tokens").asInt()).isEqualTo(100);
    }

    @Test
    void nullResponseFormatAndJsonSchemaAreOmittedViaTheSevenArgConstructorToo() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest("model-x", List.of(), 0.2, 100, null, null, null);

        String json = mapper.writeValueAsString(request);

        assertThat(json).doesNotContain("response_format");
        assertThat(json).doesNotContain("json_schema");
        assertThat(json).doesNotContain("chat_template_kwargs");
    }

    @Test
    void responseFormatIsIncludedWhenSet() throws Exception {
        JsonNode schema = mapper.readTree("{\"type\":\"json_schema\"}");
        ChatCompletionRequest request = new ChatCompletionRequest("model-x", List.of(), 0.2, 100, schema, null, null);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(request));

        assertThat(root.has("response_format")).isTrue();
        assertThat(root.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(root.has("json_schema")).isFalse();
    }

    @Test
    void jsonSchemaIsIncludedWhenSet() throws Exception {
        JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
        ChatCompletionRequest request = new ChatCompletionRequest("model-x", List.of(), 0.2, 100, null, schema, null);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(request));

        assertThat(root.has("json_schema")).isTrue();
        assertThat(root.at("/json_schema/type").asText()).isEqualTo("object");
        assertThat(root.has("response_format")).isFalse();
    }

    @Test
    void chatTemplateKwargsIsIncludedOnlyWhenSet() throws Exception {
        JsonNode noThinking = mapper.valueToTree(java.util.Map.of("enable_thinking", false));
        ChatCompletionRequest request = new ChatCompletionRequest("model-x", List.of(), 0.2, 100, null, null, noThinking);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(request));

        assertThat(root.at("/chat_template_kwargs/enable_thinking").asBoolean()).isFalse();
    }
}
