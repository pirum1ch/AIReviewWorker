package com.review.worker.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.llama.LlamaClient;
import com.review.worker.metrics.WorkerMetrics;
import com.review.worker.prompt.PromptTemplateService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-stage supplementary tests for {@link WorkerLoop}, filling gaps left by the dev suite
 * ({@code WorkerLoopIntegrationTest}) against README §9 (Gateway protocol contract),
 * {@code docs/worker-architecture.md} §6/§7/§8 (lifecycle, error taxonomy, metrics), and
 * {@code docs/worker-threat-model.md} (WSR-04/05/14).
 *
 * <p>Reuses the same "two real MockWebServer instances, path-aware Gateway dispatcher" harness style as
 * the dev suite (duplicated here rather than shared, since the original harness is private to its file).
 */
class WorkerLoopContractAndResilienceTest {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoopContractAndResilienceTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<MockWebServer> serversToClose = new ArrayList<>();
    private final List<WorkerLoop> loopsToStop = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WorkerLoop loop : loopsToStop) {
            loop.requestShutdown();
        }
        for (MockWebServer server : serversToClose) {
            try {
                server.close();
            } catch (IOException e) {
                log.debug("Ignoring MockWebServer shutdown timing issue", e);
            }
        }
        for (WorkerLoop loop : loopsToStop) {
            loop.awaitTermination(Duration.ofSeconds(5));
        }
    }

    private MockWebServer newServer() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        serversToClose.add(server);
        return server;
    }

    private void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean()).as("condition became true within " + timeout).isTrue();
    }

    private RecordedRequest takeRequestWithPath(MockWebServer server, String expectedPath, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMs = Math.max((deadline - System.nanoTime()) / 1_000_000, 1);
            RecordedRequest request = server.takeRequest(remainingMs, TimeUnit.MILLISECONDS);
            if (request == null) {
                return null;
            }
            if (expectedPath.equals(request.getPath())) {
                return request;
            }
        }
        return null;
    }

    /**
     * Same routing strategy as the dev suite's GatewayDispatcher: per-path queues, benign fallback. Also
     * keeps a non-destructive log of every request path it has ever seen (with a body snapshot), so tests
     * can assert "job A's result endpoint was never hit" without racing against/discarding an unrelated,
     * concurrently-arriving request for a different job the way consuming {@code MockWebServer}'s single
     * request queue via {@code takeRequest} would.
     */
    private static final class GatewayDispatcher extends Dispatcher {
        private static final Pattern HEARTBEAT_PATH = Pattern.compile("^/jobs/\\d+/heartbeat$");
        private static final Pattern RESULT_PATH = Pattern.compile("^/jobs/\\d+/result$");
        private static final Pattern FAIL_PATH = Pattern.compile("^/jobs/\\d+/fail$");

        private final Map<String, Queue<MockResponse>> queuesByExactPath = new ConcurrentHashMap<>();
        private final Queue<MockResponse> heartbeatQueue = new ConcurrentLinkedQueue<>();
        private final Queue<MockResponse> resultQueue = new ConcurrentLinkedQueue<>();
        private final Queue<MockResponse> failQueue = new ConcurrentLinkedQueue<>();
        private final Queue<String> seenPaths = new ConcurrentLinkedQueue<>();

        void enqueueClaim(MockResponse response) {
            queuesByExactPath.computeIfAbsent("/jobs/claim", k -> new ConcurrentLinkedQueue<>()).add(response);
        }

        void enqueueHeartbeat(MockResponse response) {
            heartbeatQueue.add(response);
        }

        void enqueueResult(MockResponse response) {
            resultQueue.add(response);
        }

        void enqueueFail(MockResponse response) {
            failQueue.add(response);
        }

        boolean everSaw(String exactPath) {
            return seenPaths.contains(exactPath);
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if (path == null) {
                return new MockResponse().setResponseCode(404);
            }
            seenPaths.add(path);
            if ("/jobs/claim".equals(path)) {
                MockResponse queued = poll(queuesByExactPath.get(path));
                return queued != null ? queued : new MockResponse().setResponseCode(204);
            }
            if (HEARTBEAT_PATH.matcher(path).matches()) {
                MockResponse queued = poll(heartbeatQueue);
                return queued != null ? queued : jsonResponse("{\"shouldContinue\":true}");
            }
            if (RESULT_PATH.matcher(path).matches()) {
                MockResponse queued = poll(resultQueue);
                return queued != null ? queued : jsonResponse("{\"reviewId\":0,\"status\":\"COMPLETED\"}");
            }
            if (FAIL_PATH.matcher(path).matches()) {
                MockResponse queued = poll(failQueue);
                return queued != null ? queued : jsonResponse("{\"accepted\":true}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private MockResponse poll(Queue<MockResponse> queue) {
            return queue == null ? null : queue.poll();
        }

        private MockResponse jsonResponse(String body) {
            return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body);
        }
    }

    private static final class Harness {
        final WorkerProperties properties;
        final WorkerMetrics metrics;
        final io.micrometer.core.instrument.MeterRegistry registry;
        final WorkerLoop workerLoop;
        final GatewayDispatcher gatewayDispatcher;

        Harness(WorkerProperties properties, WorkerMetrics metrics, io.micrometer.core.instrument.MeterRegistry registry,
                WorkerLoop workerLoop, GatewayDispatcher gatewayDispatcher) {
            this.properties = properties;
            this.metrics = metrics;
            this.registry = registry;
            this.workerLoop = workerLoop;
            this.gatewayDispatcher = gatewayDispatcher;
        }
    }

    private Harness newHarness(MockWebServer gatewayServer, MockWebServer llamaServer,
                                io.micrometer.core.instrument.MeterRegistry registry,
                                Consumer<WorkerProperties> customizer) {
        GatewayDispatcher gatewayDispatcher = new GatewayDispatcher();
        gatewayServer.setDispatcher(gatewayDispatcher);

        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl(gatewayServer.url("/").toString());
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setAllowInsecureGateway(true);
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl(llamaServer.url("/").toString());
        properties.getLlama().setModel("test-model");
        properties.getNetwork().setPollIntervalMs(30);
        properties.getNetwork().setGatewayTimeoutSec(2);
        properties.getNetwork().setRequestTimeoutSec(5);
        properties.getHeartbeat().setIntervalSec(1); // minimum allowed by validation
        customizer.accept(properties);

        HttpClient sharedHttpClient = HttpClient.newHttpClient();
        RestClient gatewayRestClient = RestClient.builder()
                .baseUrl(properties.getGateway().getUrl())
                .requestFactory(jdkFactory(sharedHttpClient, properties.getNetwork().getGatewayTimeoutSec()))
                .defaultHeader("Authorization", "Bearer " + properties.getGateway().getApiKey())
                .build();
        RestClient llamaRestClient = RestClient.builder()
                .baseUrl(properties.getLlama().getUrl())
                .requestFactory(jdkFactory(sharedHttpClient, properties.getNetwork().getRequestTimeoutSec()))
                .build();

        GatewayClient gatewayClient = new GatewayClient(gatewayRestClient);
        LlamaClient llamaClient = new LlamaClient(llamaRestClient, sharedHttpClient, new ObjectMapper(), properties);
        PromptTemplateService promptTemplateService = new PromptTemplateService(properties);
        HeartbeatScheduler heartbeatScheduler = new HeartbeatScheduler(gatewayClient, properties);
        WorkerMetrics metrics = new WorkerMetrics(registry);

        WorkerLoop workerLoop = new WorkerLoop(gatewayClient, llamaClient, promptTemplateService,
                new com.review.worker.llama.DecoderConstraintResolver(new com.fasterxml.jackson.databind.ObjectMapper(), properties),
                heartbeatScheduler, metrics, properties);
        loopsToStop.add(workerLoop);
        return new Harness(properties, metrics, registry, workerLoop, gatewayDispatcher);
    }

    private Harness newHarness(MockWebServer gatewayServer, MockWebServer llamaServer, Consumer<WorkerProperties> customizer) {
        return newHarness(gatewayServer, llamaServer, new SimpleMeterRegistry(), customizer);
    }

    private JdkClientHttpRequestFactory jdkFactory(HttpClient httpClient, int readTimeoutSec) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        return factory;
    }

    private double counterValue(io.micrometer.core.instrument.MeterRegistry registry, String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long timerCount(io.micrometer.core.instrument.MeterRegistry registry, String name) {
        Timer timer = registry.find(name).timer();
        return timer == null ? 0L : timer.count();
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json").setBody(body);
    }

    // =================================================================================================
    // 1. Full-cycle Gateway contract conformance (README §9)
    // =================================================================================================

    @Test
    void claimHeartbeatAndResultShareTheSameWorkerIdAndResultHasExactContractFields() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> {
            p.getWorker().setId("worker-conformance-1");
            p.getBackend().setId("backend-conformance-1");
        });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":501,"reviewId":501,"payload":{"diff":"diff --git a/A.java","promptVersion":"v1"}}
                """));
        // Delay long enough (~1.3s) that at least one 1s-cadence heartbeat fires before llama answers.
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(1300, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"role":"assistant","content":"[]"}}],
                         "usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}
                        """));

        long startedAtMs = System.currentTimeMillis();
        harness.workerLoop.start();

        RecordedRequest claimRequest = takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3));
        assertThat(claimRequest).isNotNull();
        JsonNode claimBody = MAPPER.readTree(claimRequest.getBody().readUtf8());
        assertThat(claimBody.get("backendId").asText()).isEqualTo("backend-conformance-1");
        assertThat(claimBody.get("workerId").asText()).isEqualTo("worker-conformance-1");

        RecordedRequest heartbeatRequest = takeRequestWithPath(gatewayServer, "/jobs/501/heartbeat", Duration.ofSeconds(3));
        assertThat(heartbeatRequest).as("at least one heartbeat during the delayed llama call").isNotNull();
        JsonNode heartbeatBody = MAPPER.readTree(heartbeatRequest.getBody().readUtf8());
        assertThat(heartbeatBody.get("workerId").asText()).isEqualTo("worker-conformance-1");

        RecordedRequest resultRequest = takeRequestWithPath(gatewayServer, "/jobs/501/result", Duration.ofSeconds(3));
        assertThat(resultRequest).isNotNull();
        long observedTotalMs = System.currentTimeMillis() - startedAtMs;

        JsonNode resultBody = MAPPER.readTree(resultRequest.getBody().readUtf8());
        // finishReason (SRO-42/43) is a deliberate new field on the result contract -- propagated
        // end-to-end from llama-server's finish_reason, nullable, whitelist-parsed on the Gateway side.
        Set<String> expectedFields = Set.of(
                "workerId", "rawResponse", "promptTokens", "completionTokens", "durationMs", "model",
                "finishReason");
        Set<String> actualFields = new java.util.HashSet<>();
        resultBody.fieldNames().forEachRemaining(actualFields::add);
        assertThat(actualFields).as("result payload fields exactly match the Gateway contract")
                .isEqualTo(expectedFields);

        assertThat(resultBody.get("workerId").asText()).isEqualTo("worker-conformance-1");
        assertThat(resultBody.get("rawResponse").asText()).isEqualTo("[]");
        assertThat(resultBody.get("promptTokens").asInt()).isEqualTo(11);
        assertThat(resultBody.get("completionTokens").asInt()).isEqualTo(3);
        assertThat(resultBody.get("model").asText()).isEqualTo("test-model");

        long reportedDurationMs = resultBody.get("durationMs").asLong();
        // Plausibility: at least the ~1.3s llama delay we injected, and not absurdly larger than the
        // observed wall-clock time for the whole claim->result window.
        assertThat(reportedDurationMs).isGreaterThanOrEqualTo(1200L);
        assertThat(reportedDurationMs).isLessThanOrEqualTo(observedTotalMs + 500L);
    }

    // =================================================================================================
    // 2. Sequential multi-job processing: no state leakage between jobs
    // =================================================================================================

    @Test
    void abortedJobDoesNotLeakAbortStateIntoTheNextSuccessfulJob() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        // Job A (jobId=10): immediately told to stop; llama "hangs" so only cancellation ends it.
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":10,"reviewId":10,"payload":{"diff":"diff-A","promptVersion":"v1"}}
                """));
        for (int i = 0; i < 5; i++) {
            harness.gatewayDispatcher.enqueueHeartbeat(jsonResponse(200, "{\"shouldContinue\":false}"));
        }
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(20, TimeUnit.SECONDS)
                .setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"never-arrives\"}}]}"));

        // Job B (jobId=11): claimed right after A is abandoned; must run to completion normally, proving
        // job A's AbortSignal/HeartbeatScheduler/metrics state did not leak forward.
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":11,"reviewId":11,"payload":{"diff":"diff-B","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, """
                {"choices":[{"message":{"role":"assistant","content":"[{\\"file\\":\\"B.java\\"}]"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """));
        harness.gatewayDispatcher.enqueueResult(jsonResponse(200, "{\"reviewId\":11,\"status\":\"COMPLETED\"}"));

        harness.workerLoop.start();

        // Note: everything below is asserted via the dispatcher's non-destructive "did we ever see this
        // path" log, NOT via MockWebServer's single-consumer takeRequest() queue for the gatewayServer --
        // job B's claim/result can (and does) arrive concurrently with the "job A never got a result"
        // check, and takeRequest()-based polling would silently discard/lose that unrelated request from
        // the shared queue, corrupting later assertions about job B. llamaServer has only one call per
        // job in flight at a time here, so takeRequest() is safe to use there.
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull(); // A's llama call issued
        awaitTrue(Duration.ofSeconds(3), () -> harness.gatewayDispatcher.everSaw("/jobs/10/heartbeat"));
        awaitTrue(Duration.ofSeconds(3), () -> llamaServer.getRequestCount() >= 2); // B's llama call issued

        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull(); // B's llama call, not pre-cancelled

        awaitTrue(Duration.ofSeconds(3), () -> harness.gatewayDispatcher.everSaw("/jobs/11/result"));
        assertThat(harness.gatewayDispatcher.everSaw("/jobs/10/result"))
                .as("job A must never post a result").isFalse();

        awaitTrue(Duration.ofSeconds(2), () -> counterValue(harness.registry, "worker.jobs.completed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs")).isEqualTo(2.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(0.0);
    }

    // =================================================================================================
    // 3. Abort/failure edge matrix
    // =================================================================================================

    @Test
    void llamaConnectionRefusedAbandonsJobAndIncrementsFailedMetric() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });
        // Close llama's socket immediately: every chat-completion attempt gets connection-refused.
        llamaServer.shutdown();

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":20,"reviewId":20,"payload":{"diff":"d","promptVersion":"v1"}}
                """));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.jobs.failed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/20/result", Duration.ofMillis(300))).isNull();
    }

    @Test
    void llamaMalformedJsonAbandonsJobAndIncrementsFailedMetric() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":21,"reviewId":21,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{not valid json at all"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.jobs.failed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/21/result", Duration.ofMillis(300))).isNull();
    }

    @Test
    void llamaEmptyChoicesAbandonsJobAndIncrementsFailedMetric() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":22,"reviewId":22,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[],\"usage\":null}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.jobs.failed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/22/result", Duration.ofMillis(300))).isNull();
    }

    @Test
    void heartbeat403AbortsJobMidGeneration() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":23,"reviewId":23,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        for (int i = 0; i < 5; i++) {
            harness.gatewayDispatcher.enqueueHeartbeat(new MockResponse().setResponseCode(403));
        }
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(20, TimeUnit.SECONDS).setResponseCode(200).setBody("{}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/23/heartbeat", Duration.ofSeconds(3))).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/23/result", Duration.ofMillis(1500))).isNull();

        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(0.0);
    }

    @Test
    void heartbeat404AbortsJobMidGeneration() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":24,"reviewId":24,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        for (int i = 0; i < 5; i++) {
            harness.gatewayDispatcher.enqueueHeartbeat(new MockResponse().setResponseCode(404));
        }
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(20, TimeUnit.SECONDS).setResponseCode(200).setBody("{}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/24/heartbeat", Duration.ofSeconds(3))).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/24/result", Duration.ofMillis(1500))).isNull();

        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(0.0);
    }

    /**
     * QA/SAST fix verification (was a defect demonstration; see {@code docs/security/worker-sast-report.md}
     * FW-06 and the accompanying QA finding): {@code WorkerLoop.runInference} now routes
     * {@code submitResultWithRedelivery(...)}'s outcome through a {@code RedeliveryOutcome} enum and only
     * calls {@code metrics.incrementJobsCompleted()} when the Gateway actually acknowledged the result
     * (DELIVERED); the escape hatch where redelivery is interrupted mid-backoff (the exact mechanism
     * {@code GracefulShutdown.abandonCurrentJob()} uses once its grace period elapses, architecture §9) is
     * routed to {@code metrics.incrementJobsFailed()} instead. This test proves (a) an interrupt during
     * the redelivery backoff sleep stops further redelivery attempts promptly, and (b) the job is now
     * correctly counted as failed, never as completed, since its result was never actually accepted by
     * the Gateway.
     */
    @Test
    void interruptDuringResultRedeliveryStopsRedeliveryAndCountsAsFailedNotCompleted() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer,
                p -> p.getNetwork().setGatewayTimeoutSec(1));

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":25,"reviewId":25,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));
        // Gateway result endpoint never responds at all -> every redelivery attempt times out and backs off.

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();

        // Prime the dispatcher so subsequent (there should be none) attempts don't hang the test.
        for (int i = 0; i < 10; i++) {
            harness.gatewayDispatcher.enqueueResult(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        }

        RecordedRequest firstAttempt = takeRequestWithPath(gatewayServer, "/jobs/25/result", Duration.ofSeconds(3));
        assertThat(firstAttempt).as("first redelivery attempt").isNotNull();

        // Simulate what GracefulShutdown does once its grace period elapses: force-abandon the in-flight job.
        harness.workerLoop.abandonCurrentJob();

        // No further redelivery attempts should arrive once the thread has been interrupted.
        RecordedRequest secondAttempt = takeRequestWithPath(gatewayServer, "/jobs/25/result", Duration.ofSeconds(2));
        assertThat(secondAttempt).as("redelivery must stop once interrupted, not keep retrying").isNull();

        // Fixed behavior: since job 25 never received a 200/403/404, it must be counted as failed, never
        // as completed (RedeliveryOutcome.ABANDONED routes to metrics.incrementJobsFailed()).
        awaitTrue(Duration.ofSeconds(2), () -> counterValue(harness.registry, "worker.jobs") == 1.0);
        awaitTrue(Duration.ofSeconds(2), () -> counterValue(harness.registry, "worker.jobs.failed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed"))
                .as("an interrupted/abandoned result redelivery must never be counted as completed")
                .isEqualTo(0.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(1.0);
    }

    @Test
    void resultSubmission403StopsRedeliveryImmediatelyWithoutRetry() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":26,"reviewId":26,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));
        harness.gatewayDispatcher.enqueueResult(new MockResponse().setResponseCode(403));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/26/result", Duration.ofSeconds(3))).isNotNull();

        // A second attempt must never arrive: 403 is terminal, not retried.
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/26/result", Duration.ofMillis(800))).isNull();
        assertThat(counterValue(harness.registry, "worker.gateway.errors")).isEqualTo(0.0);
    }

    @Test
    void resultSubmission404StopsRedeliveryImmediatelyWithoutRetry() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":27,"reviewId":27,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));
        harness.gatewayDispatcher.enqueueResult(new MockResponse().setResponseCode(404));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/27/result", Duration.ofSeconds(3))).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/27/result", Duration.ofMillis(800))).isNull();
        assertThat(counterValue(harness.registry, "worker.gateway.errors")).isEqualTo(0.0);
    }

    // =================================================================================================
    // 4. Oversize handling (WSR-04/05), full WorkerLoop path (startChatCompletion/parseResponse, not the
    //    sync chatCompletion() the dev suite's LlamaClientTest exercises)
    // =================================================================================================

    @Test
    void oversizedLlamaResponseIsAbandonedEndToEndWithoutHangingOrSubmittingAResult() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer,
                p -> p.getWorker().getLimits().setMaxResponseBytes(1000));

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":30,"reviewId":30,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        String hugeContent = "x".repeat(200_000); // far beyond the 1000-byte cap
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + hugeContent + "\"}}]}";
        llamaServer.enqueue(jsonResponse(200, body));

        long startedAt = System.nanoTime();
        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();

        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.jobs.failed") == 1.0);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).as("must fail fast, not buffer/wait on the oversized body").isLessThan(5_000);

        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/30/result", Duration.ofMillis(300))).isNull();
    }

    // =================================================================================================
    // 5. Prompt correctness reaching the actual llama HTTP request body verbatim
    // =================================================================================================

    @Test
    void specialCharactersAndConfigParamsReachTheLlamaRequestBodyUnmangled() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> {
            p.getLlama().setModel("default-model");
            p.getLlama().setTemperature(0.2);
            p.getLlama().setMaxTokens(2048);
        });

        String trickyDiff = "diff --git a/Weird.java\n"
                + "+String s = \"${T(java.lang.Runtime).exec('x')}\";\n"
                + "+// {{7*7}} #{7*7} \\n backslash-n-literal \\\\ double-backslash\n"
                + "+// unicode: héllo wörld ключ 日本語 🎉\n";
        String claimPayload = MAPPER.writeValueAsString(Map.of(
                "jobId", 40, "reviewId", 40,
                "payload", Map.of("diff", trickyDiff, "promptVersion", "with-overrides")));
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, claimPayload));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));

        harness.workerLoop.start();

        RecordedRequest llamaRequest = llamaServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(llamaRequest).isNotNull();
        assertThat(llamaRequest.getPath()).isEqualTo("/v1/chat/completions");

        JsonNode requestBody = MAPPER.readTree(llamaRequest.getBody().readUtf8());
        // Config/template-override fields actually present on the wire.
        assertThat(requestBody.get("model").asText()).isEqualTo("template-model");
        assertThat(requestBody.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(requestBody.get("max_tokens").asInt()).isEqualTo(512);

        JsonNode messages = requestBody.get("messages");
        assertThat(messages).isNotNull();
        assertThat(messages.isArray()).isTrue();
        boolean sawSystem = false;
        boolean sawUser = false;
        for (JsonNode message : messages) {
            if ("system".equals(message.get("role").asText())) {
                sawSystem = true;
                assertThat(message.get("content").asText()).contains("test reviewer");
            }
            if ("user".equals(message.get("role").asText())) {
                sawUser = true;
                String content = message.get("content").asText();
                // Every special-char sequence must survive literal substitution, unmangled: no template
                // engine ever touches the diff (WSR-02), and JSON round-tripping must not corrupt it.
                assertThat(content).contains("${T(java.lang.Runtime).exec('x')}");
                assertThat(content).contains("{{7*7}}");
                assertThat(content).contains("#{7*7}");
                assertThat(content).contains("\\n backslash-n-literal \\\\ double-backslash");
                assertThat(content).contains("héllo wörld ключ 日本語 🎉");
                assertThat(content).doesNotContain("{{DIFF}}");
            }
        }
        assertThat(sawSystem).as("system message present").isTrue();
        assertThat(sawUser).as("user message present").isTrue();
    }

    // =================================================================================================
    // 6. Resilience timing: claim backoff growth (real timestamps) + capping (algorithm-level)
    // =================================================================================================

    @Test
    void claimBackoffGrowsAcrossSuccessiveGatewayFailures() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        // A larger base than earlier drafts of this test used: a per-request fixed overhead (connection
        // setup, MockWebServer dispatch, exception mapping) of ~150-250ms was observed to swamp a tiny
        // (e.g. 20ms) base backoff, making the exponential signal indistinguishable from noise. 300ms
        // keeps the doubling sequence (300/600/1200/2400ms) comfortably above that fixed overhead.
        long pollIntervalMs = 300;
        Harness harness = newHarness(gatewayServer, llamaServer, p -> p.getNetwork().setPollIntervalMs(pollIntervalMs));

        // Every claim attempt gets a 500 -> GatewayUnavailableException -> exponential backoff.
        for (int i = 0; i < 6; i++) {
            harness.gatewayDispatcher.enqueueClaim(new MockResponse().setResponseCode(500));
        }

        harness.workerLoop.start();

        long[] arrivalNanos = new long[5];
        for (int i = 0; i < arrivalNanos.length; i++) {
            assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(10))).isNotNull();
            arrivalNanos[i] = System.nanoTime();
        }

        long gap1 = (arrivalNanos[1] - arrivalNanos[0]) / 1_000_000;
        long gap2 = (arrivalNanos[2] - arrivalNanos[1]) / 1_000_000;
        long gap3 = (arrivalNanos[3] - arrivalNanos[2]) / 1_000_000;
        long gap4 = (arrivalNanos[4] - arrivalNanos[3]) / 1_000_000;
        log.info("Observed claim-retry gaps (ms): {} {} {} {}", gap1, gap2, gap3, gap4);

        // Loose, additive-tolerance bounds (not a tight multiplicative ratio, which is sensitive to fixed
        // per-request overhead at small magnitudes): each gap must be no smaller than the previous minus a
        // generous jitter allowance, and the overall sequence must have grown substantially (roughly
        // doubling: pollIntervalMs, 2x, 4x, 8x, ...), not stayed flat or shrunk.
        long jitterToleranceMs = 150;
        assertThat(gap2).isGreaterThanOrEqualTo(gap1 - jitterToleranceMs);
        assertThat(gap3).isGreaterThanOrEqualTo(gap2 - jitterToleranceMs);
        assertThat(gap4).isGreaterThanOrEqualTo(gap3 - jitterToleranceMs);
        assertThat(gap4).as("backoff must have grown substantially, not stayed flat")
                .isGreaterThan(gap1 * 2);

        // The metric increment happens fractionally after the request is received (response round-trip +
        // exception mapping), so allow it a moment to catch up to the 5 requests already observed above.
        awaitTrue(Duration.ofSeconds(2), () -> counterValue(harness.registry, "worker.gateway.errors") >= 5.0);
    }

    @Test
    void claimBackoffAlgorithmDoublesAndCapsAtSixtySeconds() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> p.getNetwork().setPollIntervalMs(1000));

        Method nextBackoff = WorkerLoop.class.getDeclaredMethod("nextBackoff", long.class);
        nextBackoff.setAccessible(true);

        long previous = 0;
        long lastValue = 0;
        boolean sawDoubling = false;
        for (int i = 0; i < 30; i++) {
            long next = (long) nextBackoff.invoke(harness.workerLoop, previous);
            assertThat(next).as("must never exceed the 60s cap").isLessThanOrEqualTo(60_000L);
            assertThat(next).as("must never go backwards").isGreaterThanOrEqualTo(previous == 0 ? 0 : Math.min(previous, next));
            if (previous > 0 && next < 60_000L) {
                assertThat(next).isEqualTo(previous * 2);
                sawDoubling = true;
            }
            previous = next;
            lastValue = next;
        }

        assertThat(sawDoubling).as("the algorithm must actually double, not just clamp immediately").isTrue();
        assertThat(lastValue).as("must converge to exactly the 60s cap").isEqualTo(60_000L);
    }

    // =================================================================================================
    // 7. Metrics end-to-end: scripted (1 completed, 1 failed, 1 aborted) sequence -> exact values
    // =================================================================================================

    @Test
    void scriptedSequenceOfCompletedFailedAndAbortedJobsProducesExactMetricValues() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Harness harness = newHarness(gatewayServer, llamaServer, prometheusRegistry, p -> { });

        // Job 1: completes normally.
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":51,"reviewId":51,"payload":{"diff":"d1","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));
        harness.gatewayDispatcher.enqueueResult(jsonResponse(200, "{\"reviewId\":51,\"status\":\"COMPLETED\"}"));

        // Job 2: llama returns malformed JSON -> failed.
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":52,"reviewId":52,"payload":{"diff":"d2","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(new MockResponse()
                .setResponseCode(200).addHeader("Content-Type", "application/json").setBody("{not json"));

        // Job 3: heartbeat says stop mid-generation -> aborted (neither completed nor failed).
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":53,"reviewId":53,"payload":{"diff":"d3","promptVersion":"v1"}}
                """));
        for (int i = 0; i < 5; i++) {
            harness.gatewayDispatcher.enqueueHeartbeat(jsonResponse(200, "{\"shouldContinue\":false}"));
        }
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(20, TimeUnit.SECONDS).setResponseCode(200).setBody("{}"));

        harness.workerLoop.start();

        awaitTrue(Duration.ofSeconds(10), () -> counterValue(harness.registry, "worker.jobs") == 3.0);
        // Give the aborted job's cancellation a moment to fully settle before asserting final values.
        awaitTrue(Duration.ofSeconds(5), () ->
                counterValue(harness.registry, "worker.jobs.completed") + counterValue(harness.registry, "worker.jobs.failed") == 2.0);

        assertThat(counterValue(harness.registry, "worker.jobs")).isEqualTo(3.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(1.0);
        assertThat(counterValue(harness.registry, "worker.gateway.errors")).isEqualTo(0.0);
        assertThat(timerCount(harness.registry, "worker.llama.duration"))
                .as("only the genuinely-completed job reaches metrics.recordLlamaDuration "
                        + "(the malformed-JSON job fails inside parseResponse, before that call; the "
                        + "aborted job returns before it too)")
                .isEqualTo(1L);

        String scraped = prometheusRegistry.scrape();
        assertThat(scraped).contains("worker_jobs_total");
        assertThat(scraped).contains("worker_jobs_completed_total");
        assertThat(scraped).contains("worker_jobs_failed_total");
        assertThat(scraped).contains("worker_llama_duration_seconds_count");
        assertThat(scraped).contains("worker_gateway_errors_total");
        assertThat(scraped).contains("worker_uptime_seconds");
    }

    // =================================================================================================
    // 8. Worker Observability & Claim Latency (WOC-34/WOC-35/WOC-37, WOR-05): explicit failure reporting
    // =================================================================================================

    @Test
    void malformedLlamaResponseReportsFailureWithClassifiedReasonAndNoExceptionMessageLeak() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":60,"reviewId":60,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        // WOR-05: the marker below would, pre-fix, quote verbatim into a Jackson JsonParseException
        // message ("...was expecting ... at [Source: ... SECRETMARKER ...]") -- it must never reach the
        // Gateway.
        llamaServer.enqueue(jsonResponse(200, "{\"choices\": SECRETMARKER not valid json"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();

        RecordedRequest failRequest = takeRequestWithPath(gatewayServer, "/jobs/60/fail", Duration.ofSeconds(3));
        assertThat(failRequest).as("WOC-34: a LlamaException must trigger a best-effort /jobs/{id}/fail report").isNotNull();

        String rawBody = failRequest.getBody().readUtf8();
        assertThat(rawBody).as("WOR-05: detail must be a fixed Worker-side constant, never the exception message")
                .doesNotContain("SECRETMARKER");
        JsonNode failBody = MAPPER.readTree(rawBody);
        assertThat(failBody.get("workerId").asText()).isEqualTo("worker-1");
        assertThat(failBody.get("reason").asText()).isEqualTo("LLM_ERROR");

        awaitTrue(Duration.ofSeconds(3), () -> counterValue(harness.registry, "worker.failures.reported") == 1.0);
    }

    @Test
    void llamaEmptyChoicesReportsLlmEmptyResponseReason() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":61,"reviewId":61,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[],\"usage\":null}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();

        RecordedRequest failRequest = takeRequestWithPath(gatewayServer, "/jobs/61/fail", Duration.ofSeconds(3));
        assertThat(failRequest).isNotNull();
        JsonNode failBody = MAPPER.readTree(failRequest.getBody().readUtf8());
        assertThat(failBody.get("reason").asText()).isEqualTo("LLM_EMPTY_RESPONSE");
    }

    @Test
    void abortedJobNeverSendsAFailureReport() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        // WOC-37: heartbeat says stop mid-generation -> the job is aborted, not "failed"; no /fail call.
        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":62,"reviewId":62,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        for (int i = 0; i < 5; i++) {
            harness.gatewayDispatcher.enqueueHeartbeat(jsonResponse(200, "{\"shouldContinue\":false}"));
        }
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(20, TimeUnit.SECONDS).setResponseCode(200).setBody("{}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        awaitTrue(Duration.ofSeconds(3), () -> harness.gatewayDispatcher.everSaw("/jobs/62/heartbeat"));

        // Give the abort a moment to fully settle, then assert no /fail request ever arrived.
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/62/fail", Duration.ofMillis(800))).isNull();
        assertThat(counterValue(harness.registry, "worker.failures.reported")).isEqualTo(0.0);
    }

    @Test
    void resultRedeliveryAbandonedByShutdownNeverSendsAFailureReport() throws Exception {
        // WOC-37: RedeliveryOutcome.ABANDONED must not report -- a result already exists and the thread
        // is interrupted anyway.
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> p.getNetwork().setGatewayTimeoutSec(1));

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":63,"reviewId":63,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));
        for (int i = 0; i < 10; i++) {
            harness.gatewayDispatcher.enqueueResult(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        }

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/63/result", Duration.ofSeconds(3))).isNotNull();

        harness.workerLoop.abandonCurrentJob();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/63/fail", Duration.ofMillis(800))).isNull();
    }

    @Test
    void gatewayRejectingTheFailureReportIsSwallowedAndCountedAsAGatewayError() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":64,"reviewId":64,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[],\"usage\":null}"));
        // Simulate an older Gateway build that has no such endpoint (WOC-35).
        harness.gatewayDispatcher.enqueueFail(new MockResponse().setResponseCode(404));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/64/fail", Duration.ofSeconds(3))).isNotNull();

        // Single best-effort attempt: no redelivery loop, swallowed, counted, worker keeps running.
        awaitTrue(Duration.ofSeconds(3), () -> counterValue(harness.registry, "worker.gateway.errors") >= 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(1.0);
        assertThat(harness.workerLoop.isRunning()).isTrue();
    }
}
