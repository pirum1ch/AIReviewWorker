package com.review.worker.core;

import com.review.worker.config.WorkerProperties;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.HeartbeatOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HeartbeatScheduler}'s tick logic (WSR-15): invokes {@link HeartbeatScheduler#tick}
 * directly rather than waiting on a real {@code ScheduledExecutorService} cadence, so the whole suite is
 * sleep-free and instantaneous.
 */
class HeartbeatSchedulerTest {

    private GatewayClient gatewayClient;
    private HeartbeatScheduler scheduler;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(GatewayClient.class);
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("test-model");
        scheduler = new HeartbeatScheduler(gatewayClient, properties);
    }

    @Test
    void shouldContinueTrueDoesNotAbort() {
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.accepted(true));
        AbortSignal abortSignal = new AbortSignal();

        scheduler.tick(1L, "worker-1", abortSignal, new AtomicInteger(), System.currentTimeMillis(), new AtomicInteger());

        assertThat(abortSignal.isAborted()).isFalse();
    }

    @Test
    void shouldContinueFalseAborts() {
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.accepted(false));
        AbortSignal abortSignal = new AbortSignal();

        scheduler.tick(1L, "worker-1", abortSignal, new AtomicInteger(), System.currentTimeMillis(), new AtomicInteger());

        assertThat(abortSignal.isAborted()).isTrue();
    }

    @Test
    void notFoundAborts() {
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.notFound());
        AbortSignal abortSignal = new AbortSignal();

        scheduler.tick(1L, "worker-1", abortSignal, new AtomicInteger(), System.currentTimeMillis(), new AtomicInteger());

        assertThat(abortSignal.isAborted()).isTrue();
    }

    @Test
    void forbiddenAborts() {
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.forbidden());
        AbortSignal abortSignal = new AbortSignal();

        scheduler.tick(1L, "worker-1", abortSignal, new AtomicInteger(), System.currentTimeMillis(), new AtomicInteger());

        assertThat(abortSignal.isAborted()).isTrue();
    }

    @Test
    void aSingleTickExceptionDoesNotAbortAndDoesNotPropagate() {
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenThrow(new RuntimeException("boom"));
        AbortSignal abortSignal = new AbortSignal();
        AtomicInteger failures = new AtomicInteger();

        scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());

        assertThat(abortSignal.isAborted()).isFalse();
        assertThat(failures.get()).isEqualTo(1);
    }

    @Test
    void repeatedTickFailuresFailSafeAbortTheJob() {
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenThrow(new RuntimeException("boom"));
        AbortSignal abortSignal = new AbortSignal();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < HeartbeatScheduler.MAX_CONSECUTIVE_FAILURES; i++) {
            scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());
        }

        assertThat(abortSignal.isAborted()).isTrue();
        assertThat(failures.get()).isEqualTo(HeartbeatScheduler.MAX_CONSECUTIVE_FAILURES);
    }

    @Test
    void aSuccessfulTickResetsTheFailureCounter() {
        AbortSignal abortSignal = new AbortSignal();
        AtomicInteger failures = new AtomicInteger();

        when(gatewayClient.heartbeat(anyLong(), anyString())).thenThrow(new RuntimeException("boom"));
        scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());
        scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());
        assertThat(failures.get()).isEqualTo(2);

        reset(gatewayClient);
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.accepted(true));
        scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());

        assertThat(failures.get()).isZero();
        assertThat(abortSignal.isAborted()).isFalse();

        // Two more failures after the reset must NOT yet reach the fail-safe threshold on their own.
        when(gatewayClient.heartbeat(anyLong(), anyString())).thenThrow(new RuntimeException("boom again"));
        scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());
        scheduler.tick(1L, "worker-1", abortSignal, failures, System.currentTimeMillis(), new AtomicInteger());
        assertThat(abortSignal.isAborted()).isFalse();
    }

    @Test
    void startAndStopLifecycleDoesNotThrowAndStopIsIdempotent() {
        // stop() before any start() must be a safe no-op.
        scheduler.stop();

        when(gatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.accepted(true));
        scheduler.start(1L, "worker-1", new AbortSignal());
        scheduler.stop();
        scheduler.stop(); // idempotent
    }
}
