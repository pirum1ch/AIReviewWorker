package com.review.worker.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.error.JobFailureReason;
import com.review.worker.error.LlamaException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structured Output Grammar Budget (SGB-06; threat model SOGT-03, CRITICAL; SOGB-05/06/07): exercises
 * {@link LlamaClient#parseResponse}'s non-2xx classification, on the SAME async
 * ({@code startChatCompletion}/{@code parseResponse}) path {@code WorkerLoop} actually uses -- the
 * vulnerability the threat model flagged is specific to that path's post-future body read, not the
 * synchronous {@code chatCompletion} path {@code LlamaClientTest} covers.
 */
class LlamaClientNon2xxClassificationTest {

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
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl(server.url("/").toString());
        properties.getLlama().setModel("test-model");
        properties.getNetwork().setRequestTimeoutSec(10);

        HttpClient httpClient = HttpClient.newHttpClient();
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        llamaClient = new LlamaClient(restClient, httpClient, new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private List<com.review.worker.llama.dto.ChatMessage> messages() {
        return List.of(new com.review.worker.llama.dto.ChatMessage("user", "review this diff"));
    }

    /** Mirrors {@code WorkerLoop.awaitLlamaResponse}: issue async, join the future, then parse. */
    private HttpResponse<InputStream> callAndAwaitResponse() {
        LlamaClient.AsyncCompletion call = llamaClient.startChatCompletion(messages(), "test-model", 0.1, 100);
        return call.future().join();
    }

    @Test
    void plainServerErrorClassifiesAsLlmError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"internal server error\"}"));

        HttpResponse<InputStream> response = callAndAwaitResponse();

        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .satisfies(e -> assertThat(((LlamaException) e).getReason()).isEqualTo(JobFailureReason.LLM_ERROR));
    }

    @Test
    void grammarParseFailureBodyClassifiesAsConstraintRejected() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":{\"message\":\"parse: error parsing grammar: failed to parse grammar\"}}"));

        HttpResponse<InputStream> response = callAndAwaitResponse();

        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .satisfies(e -> assertThat(((LlamaException) e).getReason())
                        .isEqualTo(JobFailureReason.CONSTRAINT_REJECTED));
    }

    @Test
    void initializeSamplersFailureBodyClassifiesAsConstraintRejectedCaseInsensitively() {
        // Real llama-server wording is mixed-case ("Failed to initialize samplers") -- matching must be
        // case-insensitive (SOGB-07).
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("srv send_error: ... error: Failed to initialize samplers: failed to parse grammar"));

        HttpResponse<InputStream> response = callAndAwaitResponse();

        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .satisfies(e -> assertThat(((LlamaException) e).getReason())
                        .isEqualTo(JobFailureReason.CONSTRAINT_REJECTED));
    }

    /**
     * SOGB-07: the matched text must never surface anywhere observable from this test's vantage point --
     * asserted here as "the exception's own message never contains the raw body", since {@code
     * LlamaException}'s message is one of the few observable surfaces {@code LlamaClient} itself controls
     * (the Worker-side-constant {@code detail} string lives in {@code WorkerLoop.DETAIL_BY_REASON}).
     */
    @Test
    void matchedErrorBodyTextNeverLeaksIntoTheExceptionMessage() {
        String secretMarker = "SECRET_BACKEND_DETAIL_MARKER";
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":\"" + secretMarker + " failed to parse grammar\"}"));

        HttpResponse<InputStream> response = callAndAwaitResponse();

        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .hasMessageNotContaining(secretMarker);
    }

    /**
     * SOGB-05 (bounded bytes): an oversized error body (well past the 8 KiB bound) whose only occurrence
     * of the marker text sits beyond that bound must NOT be detected -- proving the read never buffers
     * more than the bound to keep searching. Never blows up either; degrades to LLM_ERROR.
     */
    @Test
    void oversizedErrorBodyIsBoundedAndNeverReadsPastTheLimitToFindAMatch() {
        String hugePrefix = "x".repeat(20_000); // well past the 8192-byte bound
        server.enqueue(new MockResponse().setResponseCode(500).setBody(hugePrefix + "failed to parse grammar"));

        HttpResponse<InputStream> response = callAndAwaitResponse();

        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .satisfies(e -> assertThat(((LlamaException) e).getReason()).isEqualTo(JobFailureReason.LLM_ERROR));
    }

    /**
     * SOGB-06 (CRITICAL, bounded time): headers arrive immediately (500), but the body then stalls far
     * longer than {@code request-timeout-sec} would ever allow on the headers phase. The classification
     * read must have its OWN short deadline and must not hang the calling thread -- it degrades to
     * LLM_ERROR well within a few seconds, never waiting out the artificial 30s stall.
     */
    @Test
    void stalledErrorBodyDegradesToLlmErrorWithinASmallDeadlineAndNeverHangs() {
        // 3s stall: comfortably longer than LlamaClient's own 2s error-body read deadline (so the test
        // actually exercises that deadline, not just a fast body), but short enough that MockWebServer's
        // own 5s shutdown grace period (in @AfterEach) is not blown by the still-draining connection.
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"failed to parse grammar\"}")
                .setBodyDelay(3, TimeUnit.SECONDS));

        HttpResponse<InputStream> response = callAndAwaitResponse();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .satisfies(e -> assertThat(((LlamaException) e).getReason()).isEqualTo(JobFailureReason.LLM_ERROR));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs)
                .as("must degrade at its own ~2s deadline, well before the 3s body stall completes")
                .isLessThan(2_900L);
    }
}
