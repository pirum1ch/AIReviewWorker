package com.review.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.core.HeartbeatScheduler;
import com.review.worker.core.WorkerLoop;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.llama.LlamaClient;
import com.review.worker.metrics.WorkerMetrics;
import com.review.worker.prompt.PromptTemplateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture §9: a near-done generation must be reaped within the shutdown grace window; a
 * long-running one must be abandoned rather than block shutdown indefinitely.
 */
class GracefulShutdownIntegrationTest {

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
            } catch (IOException ignored) {
                // see WorkerLoopIntegrationTest for why this is safe to ignore
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

    private static final class GatewayDispatcher extends Dispatcher {
        private static final Pattern HEARTBEAT_PATH = Pattern.compile("^/jobs/\\d+/heartbeat$");
        private static final Pattern RESULT_PATH = Pattern.compile("^/jobs/\\d+/result$");

        private final Map<String, Queue<MockResponse>> claimQueue = new ConcurrentHashMap<>();
        private final Queue<MockResponse> resultQueue = new ConcurrentLinkedQueue<>();

        void enqueueClaim(MockResponse response) {
            claimQueue.computeIfAbsent("/jobs/claim", k -> new ConcurrentLinkedQueue<>()).add(response);
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if ("/jobs/claim".equals(path)) {
                Queue<MockResponse> queue = claimQueue.get(path);
                MockResponse queued = queue == null ? null : queue.poll();
                return queued != null ? queued : new MockResponse().setResponseCode(204);
            }
            if (HEARTBEAT_PATH.matcher(path).matches()) {
                return jsonResponse("{\"shouldContinue\":true}");
            }
            if (RESULT_PATH.matcher(path).matches()) {
                MockResponse queued = resultQueue.poll();
                return queued != null ? queued : jsonResponse("{\"reviewId\":0,\"status\":\"COMPLETED\"}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private MockResponse jsonResponse(String body) {
            return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body);
        }
    }

    private WorkerLoop buildWorkerLoop(MockWebServer gatewayServer, GatewayDispatcher dispatcher, MockWebServer llamaServer,
                                        int requestTimeoutSec) {
        gatewayServer.setDispatcher(dispatcher);
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
        properties.getNetwork().setRequestTimeoutSec(requestTimeoutSec);
        properties.getHeartbeat().setIntervalSec(1);

        HttpClient sharedHttpClient = HttpClient.newHttpClient();
        RestClient gatewayRestClient = RestClient.builder()
                .baseUrl(properties.getGateway().getUrl())
                .requestFactory(jdkFactory(sharedHttpClient, properties.getNetwork().getGatewayTimeoutSec()))
                .defaultHeader("Authorization", "Bearer " + properties.getGateway().getApiKey())
                .build();
        RestClient llamaRestClient = RestClient.builder()
                .baseUrl(properties.getLlama().getUrl())
                .requestFactory(jdkFactory(sharedHttpClient, requestTimeoutSec))
                .build();

        GatewayClient gatewayClient = new GatewayClient(gatewayRestClient);
        LlamaClient llamaClient = new LlamaClient(llamaRestClient, sharedHttpClient, new ObjectMapper(), properties);
        PromptTemplateService promptTemplateService = new PromptTemplateService(properties);
        HeartbeatScheduler heartbeatScheduler = new HeartbeatScheduler(gatewayClient, properties);
        WorkerMetrics metrics = new WorkerMetrics(new SimpleMeterRegistry());

        WorkerLoop workerLoop = new WorkerLoop(gatewayClient, llamaClient, promptTemplateService,
                new com.review.worker.llama.DecoderConstraintResolver(new com.fasterxml.jackson.databind.ObjectMapper(), properties),
                heartbeatScheduler, metrics, properties);
        loopsToStop.add(workerLoop);
        return workerLoop;
    }

    private JdkClientHttpRequestFactory jdkFactory(HttpClient httpClient, int readTimeoutSec) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        return factory;
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void reapsAQuickGenerationWithinTheGracePeriod() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        GatewayDispatcher dispatcher = new GatewayDispatcher();
        WorkerLoop workerLoop = buildWorkerLoop(gatewayServer, dispatcher, llamaServer, 5);

        dispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":1,"reviewId":1,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        llamaServer.enqueue(jsonResponse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"[]\"}}]}"));

        GracefulShutdown gracefulShutdown = new GracefulShutdown(workerLoop, "3s");
        gracefulShutdown.start();
        workerLoop.start();

        assertThat(gatewayServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull(); // claim
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull(); // chat completion

        CountDownLatch callbackLatch = new CountDownLatch(1);
        gracefulShutdown.stop(callbackLatch::countDown);

        assertThat(callbackLatch.await(4, TimeUnit.SECONDS)).as("shutdown callback invoked").isTrue();
        assertThat(gracefulShutdown.isRunning()).isFalse();

        RecordedRequest resultRequest = gatewayServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(resultRequest).isNotNull();
        assertThat(resultRequest.getPath()).isEqualTo("/jobs/1/result");
    }

    @Test
    void abandonsAJobThatOutlivesTheGracePeriod() throws Exception {
        MockWebServer gatewayServer = newServer();
        MockWebServer llamaServer = newServer();
        GatewayDispatcher dispatcher = new GatewayDispatcher();
        WorkerLoop workerLoop = buildWorkerLoop(gatewayServer, dispatcher, llamaServer, 10);

        dispatcher.enqueueClaim(jsonResponse(200, """
                {"jobId":2,"reviewId":2,"payload":{"diff":"d","promptVersion":"v1"}}
                """));
        // llama never responds within the test's timeframe -- only forced abandonment can end this job.
        llamaServer.enqueue(new MockResponse().setHeadersDelay(30, TimeUnit.SECONDS).setResponseCode(200).setBody("{}"));

        GracefulShutdown gracefulShutdown = new GracefulShutdown(workerLoop, "300ms");
        gracefulShutdown.start();
        workerLoop.start();

        assertThat(gatewayServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull(); // claim
        assertThat(llamaServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull(); // chat completion issued

        CountDownLatch callbackLatch = new CountDownLatch(1);
        long startedAt = System.nanoTime();
        gracefulShutdown.stop(callbackLatch::countDown);

        assertThat(callbackLatch.await(5, TimeUnit.SECONDS)).as("shutdown callback invoked").isTrue();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        // Must not have waited out the 30s artificial llama delay -- bounded by the 300ms grace (+ small margin).
        assertThat(elapsedMs).isLessThan(5_000);

        RecordedRequest resultRequest = gatewayServer.takeRequest(500, TimeUnit.MILLISECONDS);
        assertThat(resultRequest).isNull();
    }
}
