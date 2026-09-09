package com.review.worker.llama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.core.LlamaResult;
import com.review.worker.error.JobFailureReason;
import com.review.worker.error.LlamaException;
import com.review.worker.llama.dto.ChatCompletionRequest;
import com.review.worker.llama.dto.ChatCompletionResponse;
import com.review.worker.llama.dto.ChatMessage;
import com.review.worker.llama.dto.Choice;
import com.review.worker.llama.dto.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Worker's sole channel to the local llama-server: a single OpenAI-compatible
 * {@code POST /v1/chat/completions} call per job. Never retries (retry logic lives only in the Gateway,
 * per the architecture's non-negotiable principles) and never logs the diff or the raw completion body
 * (WSR-10) — only sizes/durations/token counts.
 *
 * <p>Offers two call shapes:
 * <ul>
 *   <li>{@link #chatCompletion} — synchronous, built on the shared {@code llamaRestClient}. Simple,
 *       blocking; used where cancellation is not needed.</li>
 *   <li>{@link #startChatCompletion} — asynchronous, built directly on the single shared
 *       {@link HttpClient} bean (architecture §5). Returns the <em>raw</em>
 *       {@code CompletableFuture<HttpResponse<InputStream>>} from {@code HttpClient.sendAsync}
 *       untouched (no {@code thenApply} chaining) specifically so that cancelling it
 *       ({@code future.cancel(true)}) tears down the underlying HTTP exchange promptly instead of
 *       waiting out the full read timeout — this is what {@code WorkerLoop}/{@code AbortSignal} rely on
 *       to interrupt a llama call mid-generation on {@code shouldContinue:false}/{@code 403}/{@code 404}.
 *       Call {@link #parseResponse} once the future completes to get the {@link LlamaResult}.</li>
 * </ul>
 */
@Component
public class LlamaClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /**
     * SGB-06/SOGB-05 (Structured Output Grammar Budget): the non-2xx error-body read used ONLY to
     * classify {@link JobFailureReason#CONSTRAINT_REJECTED} — a small, fixed bound, deliberately NOT
     * {@code worker.limits.max-response-bytes} (200000): this is just enough to see an error message,
     * not a real completion. A constant, not a config property — there is no reason for an operator to
     * tune this.
     */
    private static final long MAX_ERROR_BODY_BYTES = 8192L;
    /**
     * SGB-06/SOGB-06 (CRITICAL): this read's OWN deadline, independent of {@code
     * worker.network.request-timeout-sec} — that timeout only bounds time-to-headers on the async path
     * ({@code HttpRequest.timeout}), not time spent afterward reading a body on the calling thread. A
     * backend that sends error headers then stalls mid-body must not be able to wedge the worker-loop
     * thread past this deadline; on timeout, classification silently degrades to {@code LLM_ERROR} —
     * exactly today's behavior, never a new, less-safe outcome.
     */
    private static final Duration ERROR_BODY_READ_TIMEOUT = Duration.ofSeconds(2);
    /**
     * SGB-06/SOGB-07: fixed, case-insensitive, plain-{@code contains} match tokens (never a regex against
     * backend-controlled text — a catastrophic-backtracking invitation for nothing gained). Compared in
     * lowercase against the bounded, UTF-8-decoded error body prefix; the matched text itself is never
     * returned, logged, or stored anywhere (WOR-05/SOGT-04).
     */
    private static final List<String> CONSTRAINT_REJECTION_MARKERS = List.of(
            "failed to parse grammar", "failed to initialize samplers");

    private final RestClient llamaRestClient;
    private final HttpClient sharedHttpClient;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;
    /**
     * {@code worker.llama.enable-thinking} (default {@code true}) computed once at startup, never
     * per-request: {@code null} unless the operator explicitly opts out of thinking mode, in which case
     * every request carries {@code chat_template_kwargs: {"enable_thinking": false}}. See
     * {@code WorkerProperties.Llama#isEnableThinking} and {@link ChatCompletionRequest}'s javadoc.
     */
    private final JsonNode chatTemplateKwargs;

    public LlamaClient(@Qualifier("llamaRestClient") RestClient llamaRestClient,
                        HttpClient sharedHttpClient,
                        ObjectMapper objectMapper,
                        WorkerProperties properties) {
        this.llamaRestClient = llamaRestClient;
        this.sharedHttpClient = sharedHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatTemplateKwargs = properties.getLlama().isEnableThinking()
                ? null
                : objectMapper.valueToTree(Map.of("enable_thinking", false));
    }

    /**
     * @throws LlamaException on a 5xx/unexpected status, a connection failure, a malformed/empty body,
     *                         or a body exceeding {@code worker.limits.max-response-bytes} (WSR-04/05 —
     *                         the caller must treat this the same as any other {@code LlamaException}:
     *                         abandon the job, never submit a synthetic/partial result).
     */
    public LlamaResult chatCompletion(List<ChatMessage> messages, String model, double temperature, int maxTokens) {
        ChatCompletionRequest request = new ChatCompletionRequest(model, messages, temperature, maxTokens,
                null, null, chatTemplateKwargs);
        long startedAt = System.currentTimeMillis();
        ChatCompletionResponse response;
        try {
            response = llamaRestClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new LlamaException("llama-server returned status " + status.value());
                        }
                        try {
                            return readBounded(clientResponse.getBody());
                        } catch (BoundedInputStream.ResponseTooLargeException e) {
                            throw oversizeException(e);
                        } catch (IOException e) {
                            throw new LlamaException("Could not parse llama-server response", e);
                        }
                    });
        } catch (ResourceAccessException e) {
            throw new LlamaException("Could not reach llama-server", e);
        }
        long durationMs = System.currentTimeMillis() - startedAt;
        return toResult(response, model, durationMs);
    }

    /**
     * Issues the chat-completion request asynchronously via the shared {@link HttpClient} and returns
     * immediately with a handle on the in-flight exchange. The caller is responsible for awaiting
     * {@link AsyncCompletion#future()} (with its own timeout) and then calling {@link #parseResponse} on
     * the result; cancelling {@link AsyncCompletion#future()} aborts the underlying HTTP exchange.
     *
     * @throws LlamaException if the request body cannot be serialized (should not happen for a
     *                         validated {@link ChatMessage} list, but never assume).
     */
    public AsyncCompletion startChatCompletion(List<ChatMessage> messages, String model, double temperature,
                                                int maxTokens) {
        return startChatCompletion(messages, model, temperature, maxTokens, DecoderConstraint.NONE);
    }

    /**
     * Structured Review Output (architecture §3.3): identical to the four-argument overload, except the
     * Gateway-supplied decoder constraint (already parsed/defensively validated by {@link
     * DecoderConstraintResolver}) is attached to the request's typed {@code responseFormat}/{@code
     * jsonSchema} fields — {@link DecoderConstraint#NONE} (both {@code null}) reproduces today's
     * four-field request body byte-for-byte (SRO-10/{@code ChatCompletionRequest}'s {@code
     * @JsonInclude(NON_NULL)}).
     */
    public AsyncCompletion startChatCompletion(List<ChatMessage> messages, String model, double temperature,
                                                int maxTokens, DecoderConstraint constraint) {
        ChatCompletionRequest requestBody = new ChatCompletionRequest(model, messages, temperature, maxTokens,
                constraint.responseFormat(), constraint.jsonSchema(), chatTemplateKwargs);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(requestBody);
        } catch (IOException e) {
            throw new LlamaException("Could not serialize llama-server request", e);
        }
        int requestTimeoutSec = properties.getNetwork().getRequestTimeoutSec();
        HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri())
                .timeout(Duration.ofSeconds(requestTimeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        long startedAt = System.currentTimeMillis();
        CompletableFuture<HttpResponse<InputStream>> future =
                sharedHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        return new AsyncCompletion(future, startedAt);
    }

    /**
     * Parses a completed {@link AsyncCompletion} response. Kept as a separate step from
     * {@link #startChatCompletion} so the caller (WorkerLoop) can attach the raw future to an
     * {@code AbortSignal} the moment it exists, before any parsing work begins.
     *
     * @throws LlamaException on a non-2xx status, a malformed/empty body, or an oversized body
     *                         (WSR-04/05).
     */
    public LlamaResult parseResponse(HttpResponse<InputStream> response, String requestedModel, long durationMs) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // SGB-06 (Structured Output Grammar Budget): classify a compile-time grammar rejection so
            // review_jobs.last_error can say "the backend refused the grammar" instead of an
            // undifferentiated "llama-server call failed" -- audit-only (see classifyNon2xx's javadoc).
            JobFailureReason reason = classifyNon2xx(response.body());
            throw new LlamaException("llama-server returned status " + status, reason);
        }
        ChatCompletionResponse parsed;
        try {
            parsed = readBounded(response.body());
        } catch (BoundedInputStream.ResponseTooLargeException e) {
            throw oversizeException(e);
        } catch (IOException e) {
            throw new LlamaException("Could not parse llama-server response", e);
        }
        return toResult(parsed, requestedModel, durationMs);
    }

    /**
     * SGB-06/SOGB-05/SOGB-06 (Structured Output Grammar Budget, CRITICAL): reads a bounded prefix of a
     * non-2xx llama-server response body, off the calling (worker-loop) thread, under its own short
     * deadline, purely to classify {@link JobFailureReason#CONSTRAINT_REJECTED} vs. the existing generic
     * {@link JobFailureReason#LLM_ERROR}. This is a NEW ingestion of backend-controlled bytes on a path
     * that previously read nothing (the old code threw before ever touching {@code response.body()}), so
     * both halves of the threat model's requirement are load-bearing:
     * <ul>
     *   <li><b>bytes</b> (SOGB-05): {@link #MAX_ERROR_BODY_BYTES}, via the same {@link
     *       BoundedInputStream} the success path already uses -- never the 200000-byte
     *       {@code worker.limits.max-response-bytes} bound, which exists to hold a real completion, not
     *       an error message;</li>
     *   <li><b>time</b> (SOGB-06, the half that actually matters): {@code request-timeout-sec} only
     *       bounds time-to-<em>headers</em> on this async path -- a backend that sends error headers and
     *       then stalls mid-body would otherwise block this thread past every existing timeout, since
     *       {@code AbortSignal.future.cancel(true)} is a no-op on an already-completed future. The read
     *       runs on a short-lived daemon thread with its own {@link #ERROR_BODY_READ_TIMEOUT}; on
     *       timeout, the stream is closed (unblocking that thread's {@code read()} via
     *       {@code IOException}) and classification silently degrades to {@code LLM_ERROR} -- never a
     *       new, less-safe failure mode than doing nothing.
     * </ul>
     * Under every outcome (timeout, {@code IOException}, empty body, non-UTF-8 bytes, oversized body)
     * this method decorates a decision that is already made (the job is abandoned either way) — it never
     * changes whether the job is abandoned, only the {@code reason} attached to the report.
     *
     * <p>ponytail: a brand-new daemon {@link Thread} per call, not a shared executor -- this path only
     * runs on a non-2xx llama-server response, which is already the rare/error case; a pooled executor
     * would be unrequested infrastructure for something that happens at most once per failed attempt.
     */
    private JobFailureReason classifyNon2xx(InputStream body) {
        CompletableFuture<String> readCompletion = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                readCompletion.complete(readErrorBodyBounded(body));
            } catch (RuntimeException unexpected) {
                readCompletion.complete("");
            }
        }, "llama-error-body-read");
        reader.setDaemon(true);
        reader.start();
        try {
            String errorBody = readCompletion.get(ERROR_BODY_READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return classify(errorBody);
        } catch (TimeoutException timedOut) {
            log.debug("Timed out reading llama-server's non-2xx error body within {} -- degrading to LLM_ERROR",
                    ERROR_BODY_READ_TIMEOUT);
            closeQuietly(body);
            return JobFailureReason.LLM_ERROR;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return JobFailureReason.LLM_ERROR;
        } catch (ExecutionException unexpected) {
            return JobFailureReason.LLM_ERROR;
        }
    }

    /** @return up to {@link #MAX_ERROR_BODY_BYTES} of decoded body text, or {@code ""} on any failure/oversize. */
    private String readErrorBodyBounded(InputStream raw) {
        try (InputStream bounded = new BoundedInputStream(raw, MAX_ERROR_BODY_BYTES)) {
            return new String(bounded.readAllBytes(), StandardCharsets.UTF_8);
        } catch (BoundedInputStream.ResponseTooLargeException tooLarge) {
            // An oversized error body -- degrade to LLM_ERROR (classify("") never matches), never buffer
            // more than the bound to try to find a match anyway.
            return "";
        } catch (IOException ioFailure) {
            return "";
        }
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best-effort: the point is unblocking the reader thread's read(), not reporting this.
        }
    }

    /** Plain, case-insensitive {@code contains} — never a regex against backend-controlled text (SOGB-07). */
    private JobFailureReason classify(String errorBody) {
        if (errorBody == null || errorBody.isEmpty()) {
            return JobFailureReason.LLM_ERROR;
        }
        String lower = errorBody.toLowerCase(Locale.ROOT);
        for (String marker : CONSTRAINT_REJECTION_MARKERS) {
            if (lower.contains(marker)) {
                return JobFailureReason.CONSTRAINT_REJECTED;
            }
        }
        return JobFailureReason.LLM_ERROR;
    }

    private ChatCompletionResponse readBounded(InputStream rawBody) throws IOException {
        long maxResponseBytes = properties.getWorker().getLimits().getMaxResponseBytes();
        try (InputStream bounded = new BoundedInputStream(rawBody, maxResponseBytes)) {
            return objectMapper.readValue(bounded, ChatCompletionResponse.class);
        }
    }

    private LlamaException oversizeException(BoundedInputStream.ResponseTooLargeException cause) {
        long maxResponseBytes = properties.getWorker().getLimits().getMaxResponseBytes();
        return new LlamaException(
                "llama-server response exceeded " + maxResponseBytes + " bytes -- abandoning job", cause,
                JobFailureReason.LLM_RESPONSE_TOO_LARGE);
    }

    private URI chatCompletionsUri() {
        String base = properties.getLlama().getUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + CHAT_COMPLETIONS_PATH);
    }

    private LlamaResult toResult(ChatCompletionResponse response, String requestedModel, long durationMs) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new LlamaException("llama-server response had no choices", JobFailureReason.LLM_EMPTY_RESPONSE);
        }
        Choice firstChoice = response.choices().get(0);
        if (firstChoice.message() == null || firstChoice.message().content() == null
                || firstChoice.message().content().isEmpty()) {
            throw new LlamaException("llama-server response choice had no message content", JobFailureReason.LLM_EMPTY_RESPONSE);
        }
        Usage usage = response.usage();
        Integer promptTokens = usage != null ? usage.promptTokens() : null;
        Integer completionTokens = usage != null ? usage.completionTokens() : null;
        // SRO-42: already parsed into Choice and, until now, discarded -- propagated so the Gateway can
        // tell "max-tokens exhausted" (finish_reason=length) apart from "the model produced garbage".
        String finishReason = firstChoice.finishReason();
        log.info("llama-server completion received (durationMs={}, promptTokens={}, completionTokens={})",
                durationMs, promptTokens, completionTokens);
        return new LlamaResult(firstChoice.message().content(), promptTokens, completionTokens, durationMs,
                requestedModel, finishReason);
    }

    /**
     * Handle on an in-flight asynchronous chat-completion call: the raw, directly-cancellable future from
     * {@code HttpClient.sendAsync} plus the wall-clock start time (for {@code durationMs} accounting).
     */
    public record AsyncCompletion(CompletableFuture<HttpResponse<InputStream>> future, long startedAtMillis) {
    }
}
