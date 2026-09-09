package com.review.worker.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.llama.LlamaClient;
import com.review.worker.metrics.WorkerMetrics;
import com.review.worker.prompt.PromptTemplateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scenarios for {@link WorkerLoop} against two real (loopback-socket) {@link MockWebServer}
 * instances standing in for the Gateway and llama-server (architecture §12 — no Docker/Testcontainers).
 *
 * <p>The Gateway server uses a path-aware {@link Dispatcher} (rather than a single FIFO
 * {@code enqueue(...)} queue) because one job's lifecycle interleaves three different endpoints (claim,
 * heartbeat, result) on the same server: a plain FIFO queue would let an unrelated heartbeat tick
 * silently consume a response a test intended for the result submission, or vice versa. Each test enqueues
 * responses per-path; anything not explicitly queued falls back to a sensible default (204 for claim,
 * {@code shouldContinue:true} for heartbeat).
 *
 * <p>Every scenario uses small, injected {@code WorkerProperties} intervals (tens of milliseconds for
 * polling, the minimum allowed 1s for heartbeat cadence) and synchronizes on {@code MockWebServer}'s
 * blocking {@code takeRequest(timeout, unit)} rather than fixed {@code Thread.sleep} calls wherever
 * assertions allow it, so the suite stays fast and deterministic.
 */
class WorkerLoopIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoopIntegrationTest.class);

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
                // MockWebServer can be slow to tear down a connection that still has an in-flight,
                // artificially-delayed response; this is a test-harness timing detail, not a production
                // code failure, so it is logged and ignored rather than failing the test.
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

    /** Bounded polling helper used for assertions instead of a fixed sleep -- returns as soon as true. */
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
     * Routes each incoming request by exact path (claim, result) or by path pattern (heartbeat, whose
     * path embeds the job id), returning per-path queued responses in order, falling back to a benign
     * default once a path's queue is exhausted so the loop doesn't spuriously error out mid-assertion.
     */
    private static final class GatewayDispatcher extends Dispatcher {
        private static final Pattern HEARTBEAT_PATH = Pattern.compile("^/jobs/\\d+/heartbeat$");
        private static final Pattern RESULT_PATH = Pattern.compile("^/jobs/\\d+/result$");
        private static final Pattern FAIL_PATH = Pattern.compile("^/jobs/\\d+/fail$");

        private final Map<String, Queue<MockResponse>> queuesByExactPath = new ConcurrentHashMap<>();
        private final Queue<MockResponse> heartbeatQueue = new ConcurrentLinkedQueue<>();
        private final Queue<MockResponse> resultQueue = new ConcurrentLinkedQueue<>();
        private final Queue<MockResponse> failQueue = new ConcurrentLinkedQueue<>();

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

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if (path == null) {
                return new MockResponse().setResponseCode(404);
            }
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
                // WOC-34/WOC-35: a Worker-Observability-and-Claim-Latency-aware Gateway; a benign default
                // so existing failure-path scenarios don't spuriously start incrementing worker.gateway.errors.
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
        final SimpleMeterRegistry registry;
        final WorkerLoop workerLoop;
        final GatewayDispatcher gatewayDispatcher;

        Harness(WorkerProperties properties, WorkerMetrics metrics, SimpleMeterRegistry registry,
                WorkerLoop workerLoop, GatewayDispatcher gatewayDispatcher) {
            this.properties = properties;
            this.metrics = metrics;
            this.registry = registry;
            this.workerLoop = workerLoop;
            this.gatewayDispatcher = gatewayDispatcher;
        }
    }

    private Harness newHarness(MockWebServer gatewayServer, MockWebServer llamaServer,
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
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerMetrics metrics = new WorkerMetrics(registry);

        WorkerLoop workerLoop = new WorkerLoop(gatewayClient, llamaClient, promptTemplateService,
                new com.review.worker.llama.DecoderConstraintResolver(new com.fasterxml.jackson.databind.ObjectMapper(), properties),
                heartbeatScheduler, metrics, properties);
        loopsToStop.add(workerLoop);
        return new Harness(properties, metrics, registry, workerLoop, gatewayDispatcher);
    }

    private JdkClientHttpRequestFactory jdkFactory(HttpClient httpClient, int readTimeoutSec) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        return factory;
    }

    private double counterValue(SimpleMeterRegistry registry, String name) {
        io.micrometer.core.instrument.Counter counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json").setBody(body);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void happyPathClaimsInfersAndSubmitsResult() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":1,"reviewId":1,"payload":{"diff":"diff --git a/A.java","promptVersion":"v1"}}
                """));
        harness.gatewayDispatcher.enqueueResult(jsonResponse(200, "{\"reviewId\":1,\"status\":\"COMPLETED\"}"));
        llamaServer.enqueue(jsonResponse(200, """
                {"choices":[{"message":{"role":"assistant","content":"[]"}}],
                 "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}
                """));

        harness.workerLoop.start();

        RecordedRequest claimRequest = takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3));
        assertThat(claimRequest).isNotNull();

        RecordedRequest llamaRequest = llamaServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(llamaRequest).isNotNull();
        assertThat(llamaRequest.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(llamaRequest.getBody().readUtf8()).contains("diff --git a/A.java");

        RecordedRequest resultRequest = takeRequestWithPath(gatewayServer, "/jobs/1/result", Duration.ofSeconds(3));
        assertThat(resultRequest).isNotNull();
        assertThat(resultRequest.getBody().readUtf8()).contains("\"rawResponse\":\"[]\"");

        awaitTrue(Duration.ofSeconds(2), () -> counterValue(harness.registry, "worker.jobs.completed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs")).isEqualTo(1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(0.0);
    }

    @Test
    void pollLoopRespectsPollInterval() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        long pollIntervalMs = 200;
        Harness harness = newHarness(gatewayServer, llamaServer, p -> p.getNetwork().setPollIntervalMs(pollIntervalMs));

        harness.workerLoop.start();

        RecordedRequest first = takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3));
        long afterFirstNanos = System.nanoTime();
        RecordedRequest second = takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3));
        long afterSecondNanos = System.nanoTime();
        RecordedRequest third = takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3));
        long afterThirdNanos = System.nanoTime();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(third).isNotNull();

        long elapsedMs12 = (afterSecondNanos - afterFirstNanos) / 1_000_000;
        long elapsedMs23 = (afterThirdNanos - afterSecondNanos) / 1_000_000;
        // Each gap must include at least one full pollIntervalMs sleep (small tolerance for scheduling jitter).
        assertThat(elapsedMs12).isGreaterThanOrEqualTo(pollIntervalMs - 40);
        assertThat(elapsedMs23).isGreaterThanOrEqualTo(pollIntervalMs - 40);
    }

    @Test
    void shouldContinueFalseMidGenerationCancelsAndSubmitsNoResult() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> { });

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":7,"reviewId":7,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        // Every heartbeat tick for this job says "stop" -- the first one to fire (~1s in) triggers the abort.
        for (int i = 0; i < 5; i++) {
            harness.gatewayDispatcher.enqueueHeartbeat(jsonResponse(200, "{\"shouldContinue\":false}"));
        }
        // llama-server "hangs" (long header delay) so only cancellation -- not natural completion -- can end it.
        llamaServer.enqueue(new MockResponse()
                .setHeadersDelay(20, TimeUnit.SECONDS)
                .setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"}}]}"));

        long startedAt = System.nanoTime();
        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(takeRequestWithPath(gatewayServer, "/jobs/7/heartbeat", Duration.ofSeconds(3))).isNotNull();

        // No result must ever be submitted for this job.
        RecordedRequest resultRequest = takeRequestWithPath(gatewayServer, "/jobs/7/result", Duration.ofMillis(1500));
        assertThat(resultRequest).isNull();

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        // The 20s artificial llama delay must have been cut short by cancellation, not waited out.
        assertThat(elapsedMs).isLessThan(10_000);

        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(0.0);
    }

    @Test
    void llamaReadTimeoutAbandonsJobAndIncrementsFailedMetric() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        Harness harness = newHarness(gatewayServer, llamaServer,
                p -> p.getNetwork().setRequestTimeoutSec(1));

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":9,"reviewId":9,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(new MockResponse().setHeadersDelay(30, TimeUnit.SECONDS).setResponseCode(200).setBody("{}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();

        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.jobs.failed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.completed")).isEqualTo(0.0);

        RecordedRequest resultRequest = takeRequestWithPath(gatewayServer, "/jobs/9/result", Duration.ofMillis(300));
        assertThat(resultRequest).isNull();
    }

    @Test
    void gatewayDownDuringResultRedeliversUntilAccepted() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        // A short gatewayTimeoutSec is essential here: SocketPolicy.NO_RESPONSE never writes a single byte
        // back, so only a genuine client-side read-timeout (not a JDK-internal transparent connection
        // retry, which can otherwise silently paper over a same-connection reset) can make this scenario
        // client-visible as a real GatewayUnavailableException.
        Harness harness = newHarness(gatewayServer, llamaServer, p -> p.getNetwork().setGatewayTimeoutSec(1));

        harness.gatewayDispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":3,"reviewId":3,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));
        // First result-submission attempt: the Gateway never responds (simulated outage). Second succeeds.
        harness.gatewayDispatcher.enqueueResult(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        harness.gatewayDispatcher.enqueueResult(jsonResponse(200, "{\"reviewId\":3,\"status\":\"COMPLETED\"}"));

        harness.workerLoop.start();

        assertThat(takeRequestWithPath(gatewayServer, "/jobs/claim", Duration.ofSeconds(3))).isNotNull();
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull();

        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.jobs.completed") == 1.0);
        assertThat(counterValue(harness.registry, "worker.gateway.errors")).isGreaterThanOrEqualTo(1.0);
        assertThat(counterValue(harness.registry, "worker.jobs.failed")).isEqualTo(0.0);
    }

    @Test
    void gatewayDownDuringClaimKeepsRunningAndBacksOff() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        // Close the Gateway server immediately: every claim attempt fails fast with connection refused.
        gatewayServer.shutdown();
        Harness harness = newHarness(gatewayServer, llamaServer, p -> p.getNetwork().setPollIntervalMs(20));

        harness.workerLoop.start();

        awaitTrue(Duration.ofSeconds(5), () -> counterValue(harness.registry, "worker.gateway.errors") >= 2.0);

        assertThat(harness.workerLoop.isRunning()).isTrue();
        assertThat(counterValue(harness.registry, "worker.jobs")).isEqualTo(0.0);
    }
}
