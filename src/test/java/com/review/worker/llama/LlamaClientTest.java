package com.review.worker.llama;

import com.review.worker.config.WorkerProperties;
import com.review.worker.core.LlamaResult;
import com.review.worker.error.LlamaException;
import com.review.worker.llama.dto.ChatMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link LlamaClient} against a real (loopback-socket) HTTP server via okhttp's MockWebServer
 * (per architecture §12 — no Docker/Testcontainers on this machine): success parsing, 5xx, malformed/
 * empty bodies, and the WSR-04/05 oversize-response abandon path.
 */
class LlamaClientTest {

    private MockWebServer server;
    private LlamaClient llamaClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxResponseBytes(500);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl(server.url("/").toString());
        properties.getLlama().setModel("test-model");

        HttpClient httpClient = HttpClient.newHttpClient();
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        llamaClient = new LlamaClient(restClient, httpClient, new com.fasterxml.jackson.databind.ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private List<ChatMessage> messages() {
        return List.of(new ChatMessage("user", "review this diff"));
    }

    @Test
    void parsesSuccessfulCompletion() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"cmpl-1","choices":[{"message":{"role":"assistant","content":"[]"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                        """));

        LlamaResult result = llamaClient.chatCompletion(messages(), "test-model", 0.1, 100);

        assertThat(result.rawResponse()).isEqualTo("[]");
        assertThat(result.promptTokens()).isEqualTo(10);
        assertThat(result.completionTokens()).isEqualTo(2);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void defaultConfigSendsNoChatTemplateKwargsAtAll() throws InterruptedException {
        // worker.llama.enable-thinking defaults to true: no behavior change without an explicit
        // operator opt-out (see WorkerProperties.Llama#isEnableThinking's javadoc) -- confirms the
        // request body stays exactly what it was before this knob existed.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"role":"assistant","content":"[]"},"finish_reason":"stop"}]}
                        """));

        llamaClient.chatCompletion(messages(), "test-model", 0.1, 100);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8()).doesNotContain("chat_template_kwargs");
    }

    @Test
    void requestDisablesModelThinkingWhenExplicitlyConfigured() throws IOException, InterruptedException {
        // A "thinking" model spends part of max_tokens on a hidden reasoning block before the actual
        // answer; on a large diff that can exhaust the whole budget and leave message.content empty
        // (surfaces as LLM_EMPTY_RESPONSE even though the backend is healthy). worker.llama.enable-
        // thinking=false is the blunt, always-off opt-out for a backend where tuning reasoning_effort
        // via the backend's own --chat-template-kwargs isn't available.
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl(server.url("/").toString());
        properties.getLlama().setModel("test-model");
        properties.getLlama().setEnableThinking(false);
        HttpClient httpClient = HttpClient.newHttpClient();
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        LlamaClient thinkingDisabledClient = new LlamaClient(restClient, httpClient,
                new com.fasterxml.jackson.databind.ObjectMapper(), properties);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"role":"assistant","content":"[]"},"finish_reason":"stop"}]}
                        """));

        thinkingDisabledClient.chatCompletion(messages(), "test-model", 0.1, 100);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8())
                .contains("\"chat_template_kwargs\":{\"enable_thinking\":false}");
    }

    @Test
    void throwsLlamaExceptionOn500() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal error"));

        assertThatThrownBy(() -> llamaClient.chatCompletion(messages(), "test-model", 0.1, 100))
                .isInstanceOf(LlamaException.class);
    }

    @Test
    void throwsLlamaExceptionOnMalformedJson() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{not valid json"));

        assertThatThrownBy(() -> llamaClient.chatCompletion(messages(), "test-model", 0.1, 100))
                .isInstanceOf(LlamaException.class);
    }

    @Test
    void throwsLlamaExceptionOnEmptyChoices() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"cmpl-1\",\"choices\":[],\"usage\":null}"));

        assertThatThrownBy(() -> llamaClient.chatCompletion(messages(), "test-model", 0.1, 100))
                .isInstanceOf(LlamaException.class);
    }

    @Test
    void throwsLlamaExceptionOnMissingMessageContent() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}"));

        assertThatThrownBy(() -> llamaClient.chatCompletion(messages(), "test-model", 0.1, 100))
                .isInstanceOf(LlamaException.class);
    }

    @Test
    void abandonsOversizedResponseWithoutBufferingItAll() {
        // properties above set maxResponseBytes=500; send a body well beyond that.
        String hugeContent = "x".repeat(50_000);
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + hugeContent + "\"}}]}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body));

        assertThatThrownBy(() -> llamaClient.chatCompletion(messages(), "test-model", 0.1, 100))
                .isInstanceOf(LlamaException.class)
                .hasMessageContaining("exceeded");
    }
}
