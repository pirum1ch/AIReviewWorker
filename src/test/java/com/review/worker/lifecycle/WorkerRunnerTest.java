package com.review.worker.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.review.worker.config.WorkerProperties;
import com.review.worker.core.WorkerLoop;
import com.review.worker.error.GatewayUnavailableException;
import com.review.worker.gateway.AnnounceOutcome;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.dto.AnnounceRequest;
import com.review.worker.gateway.dto.AnnounceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backend Self-Registration (architecture §4.3, threat model BSQ-18/19/20): the announce-before-
 * {@code workerLoop.start()} sequencing and the terminal-vs-transient-vs-fatal outcome handling.
 */
class WorkerRunnerTest {

    private WorkerLoop workerLoop;
    private GatewayClient gatewayClient;
    private ApplicationArguments args;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        workerLoop = mock(WorkerLoop.class);
        gatewayClient = mock(GatewayClient.class);
        args = mock(ApplicationArguments.class);

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(WorkerRunner.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(WorkerRunner.class)).detachAppender(logAppender);
    }

    private WorkerProperties propertiesWithBackendUrl(String backendUrl) {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getBackend().setUrl(backendUrl);
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("test-model");
        properties.getNetwork().setPollIntervalMs(10L);
        return properties;
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ---- backend.url unset: no announce, straight to workerLoop.start() ----

    @Test
    void backendUrlUnsetSkipsAnnounceAndStartsTheLoop() {
        WorkerProperties properties = propertiesWithBackendUrl(null);
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(gatewayClient, never()).announce(any());
        verify(workerLoop, times(1)).start();
    }

    @Test
    void blankBackendUrlSkipsAnnounceAndStartsTheLoop() {
        WorkerProperties properties = propertiesWithBackendUrl("   ");
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(gatewayClient, never()).announce(any());
        verify(workerLoop, times(1)).start();
    }

    // ---- ACCEPTED ----

    @Test
    void acceptedActiveStartsTheLoopWithoutWarning() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(
                AnnounceOutcome.accepted(new AnnounceResponse("backend-1", "ACTIVE", true)));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(workerLoop, times(1)).start();
        assertThat(warnMessages()).noneMatch(msg -> msg.contains("no jobs will be dispatched"));
    }

    @Test
    void acceptedNonActiveStatusStartsTheLoopButWarns() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(
                AnnounceOutcome.accepted(new AnnounceResponse("backend-1", "MAINTENANCE", false)));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(workerLoop, times(1)).start();
        assertThat(warnMessages()).anyMatch(msg -> msg.contains("MAINTENANCE") && msg.contains("no jobs will be dispatched"));
    }

    @Test
    void announceRequestCarriesTheDocumentedWireShape() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(
                AnnounceOutcome.accepted(new AnnounceResponse("backend-1", "ACTIVE", true)));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        org.mockito.ArgumentCaptor<AnnounceRequest> captor = org.mockito.ArgumentCaptor.forClass(AnnounceRequest.class);
        verify(gatewayClient).announce(captor.capture());
        AnnounceRequest request = captor.getValue();
        assertThat(request.backendId()).isEqualTo("backend-1");
        assertThat(request.workerId()).isEqualTo("worker-1");
        assertThat(request.url()).isEqualTo("http://192.168.1.50:8080");
        assertThat(request.model()).isEqualTo("test-model");
    }

    // ---- BSQ-18: 403/404 are non-fatal and must NOT block startup ----

    @Test
    void rejected403StartsTheLoopAnywayAndDoesNotThrow() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(AnnounceOutcome.rejectedNonFatal(403));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(workerLoop, times(1)).start();
        assertThat(warnMessages()).anyMatch(msg -> msg.contains("403") && msg.contains("legacy mode"));
    }

    @Test
    void rejected404StartsTheLoopAnywayAndDoesNotThrow() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(AnnounceOutcome.rejectedNonFatal(404));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(workerLoop, times(1)).start();
        assertThat(warnMessages()).anyMatch(msg -> msg.contains("404") && msg.contains("legacy mode"));
    }

    // ---- 400/409/422 are fatal: startup must fail, loop must never start ----

    @Test
    void rejected400ThrowsAndNeverStartsTheLoop() {
        // QA fix: a 400 (backend.id/worker.id/llama.model failing the Gateway's bean validation) must
        // fail startup exactly like 409/422, not retry forever as if the Gateway were unreachable.
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(AnnounceOutcome.rejectedFatal(400));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        assertThatThrownBy(() -> runner.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("worker.id");

        verify(workerLoop, never()).start();
    }

    @Test
    void rejected409ThrowsAndNeverStartsTheLoop() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(AnnounceOutcome.rejectedFatal(409));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        assertThatThrownBy(() -> runner.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("worker.id");

        verify(workerLoop, never()).start();
    }

    @Test
    void rejected422ThrowsAndNeverStartsTheLoop() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(AnnounceOutcome.rejectedFatal(422));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        assertThatThrownBy(() -> runner.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("422")
                .hasMessageContaining("allowlist");

        verify(workerLoop, never()).start();
    }

    @Test
    void rejected401ThrowsAndNeverStartsTheLoop() {
        // F-BSR-05: a wrong/missing bearer token must fail startup with a message naming the likely
        // cause (GATEWAY_API_KEY), not retry forever behind a misleading "Gateway unavailable" WARN.
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenReturn(AnnounceOutcome.rejectedFatal(401));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        assertThatThrownBy(() -> runner.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("GATEWAY_API_KEY");

        verify(workerLoop, never()).start();
    }

    // ---- Gateway unreachable: retries with capped backoff, then succeeds ----

    @Test
    void gatewayUnavailableRetriesThenStartsOnceAnnounceSucceeds() {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any()))
                .thenThrow(new GatewayUnavailableException("unreachable"))
                .thenThrow(new GatewayUnavailableException("unreachable"))
                .thenReturn(AnnounceOutcome.accepted(new AnnounceResponse("backend-1", "ACTIVE", true)));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        runner.run(args);

        verify(gatewayClient, times(3)).announce(any());
        verify(workerLoop, times(1)).start();
        assertThat(warnMessages()).anyMatch(msg -> msg.contains("retrying in"));
    }

    // ---- BSQ-19: the backoff loop is interruptible via ContextClosedEvent ----

    @Test
    void contextClosedEventDuringBackoffTerminatesTheRunPromptlyWithoutStartingTheLoop() throws InterruptedException {
        WorkerProperties properties = propertiesWithBackendUrl("http://192.168.1.50:8080");
        when(gatewayClient.announce(any())).thenThrow(new GatewayUnavailableException("always down"));
        WorkerRunner runner = new WorkerRunner(workerLoop, gatewayClient, properties);

        CountDownLatch runStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runnerThread = new Thread(() -> {
            runStarted.countDown();
            try {
                runner.run(args);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "worker-runner-test-thread");
        runnerThread.setDaemon(true);
        runnerThread.start();

        assertThat(runStarted.await(2, TimeUnit.SECONDS)).isTrue();
        // Give the retry loop a moment to genuinely enter its first backoff sleep before signalling shutdown.
        Thread.sleep(100);

        runner.onApplicationEvent(new ContextClosedEvent(mock(ApplicationContext.class)));

        runnerThread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(runnerThread.isAlive())
                .as("run() must return promptly once shutdown is signalled, not block until SIGKILL")
                .isFalse();
        assertThat(failure.get()).isNull();
        verify(workerLoop, never()).start();
    }
}
