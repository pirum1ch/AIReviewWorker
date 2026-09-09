package com.review.worker.core;

import com.review.worker.config.WorkerProperties;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.dto.ClaimResponse;
import com.review.worker.gateway.dto.JobPayload;
import com.review.worker.llama.LlamaClient;
import com.review.worker.metrics.WorkerMetrics;
import com.review.worker.prompt.PromptTemplateService;
import com.review.worker.prompt.ResolvedPrompt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-WOC-03 (SAST fix round): {@code WorkerLoop.reportFailureBestEffort} must never let an unmapped
 * exception from its Gateway call kill the {@code worker-loop} thread -- exactly the silent-stall
 * failure class this feature exists to eliminate (WOC-35: "any failure is logged at WARN, counted in a
 * metric, and swallowed"). Before the fix, only {@code GatewayUnavailableException} was caught, so any
 * other {@code RuntimeException} propagated out of {@code processJob}'s only handler and out of {@code
 * runLoop}'s {@code while}, silently ending the thread with no further log output and no more claims.
 *
 * <p>Uses a fully mocked {@link GatewayClient}/{@link LlamaClient}/{@link PromptTemplateService} rather
 * than the MockWebServer harness elsewhere in this package: the defect is entirely internal to {@code
 * WorkerLoop}'s own exception handling and does not depend on any real HTTP behavior, so a direct unit
 * test is both simpler and more precise about exactly which call throws what.
 */
class WorkerLoopReportFailureCrashGuardTest {

    private WorkerProperties newProperties() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("test-model");
        properties.getNetwork().setPollIntervalMs(20L);
        return properties;
    }

    private final List<WorkerLoop> loopsToStop = new java.util.ArrayList<>();

    @AfterEach
    void stopLoops() {
        for (WorkerLoop loop : loopsToStop) {
            loop.requestShutdown();
            loop.awaitTermination(Duration.ofSeconds(5));
        }
    }

    @Test
    void anUnmappedRuntimeExceptionFromReportFailureIsSwallowedAndTheLoopKeepsClaiming() {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        LlamaClient llamaClient = mock(LlamaClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        HeartbeatScheduler heartbeatScheduler = mock(HeartbeatScheduler.class);
        WorkerMetrics metrics = mock(WorkerMetrics.class);

        ClaimResponse claimed = new ClaimResponse(1L, 1L, new JobPayload("diff", "v1", "context", List.of("sys"), null, null));
        when(gatewayClient.claim(anyString(), anyString()))
                .thenReturn(Optional.of(claimed))
                .thenReturn(Optional.empty()); // subsequent polls: idle, so the loop just keeps ticking

        when(promptTemplateService.resolve(anyString(), any(), any(), any()))
                .thenReturn(new ResolvedPrompt(List.of(), "test-model", 0.2, 100));

        // The llama call fails -> awaitLlamaResponse wraps it into a LlamaException -> processJob's
        // catch (AbandonJobException | LlamaException) triggers reportFailureBestEffort.
        CompletableFuture<HttpResponse<java.io.InputStream>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("simulated llama-server failure"));
        when(llamaClient.startChatCompletion(any(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(new LlamaClient.AsyncCompletion(failedFuture, System.currentTimeMillis()));

        // The defect under test: reportFailure throws something reportFailureBestEffort's OLD, narrow
        // catch (GatewayUnavailableException only) would never have caught.
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated unmapped Gateway client failure"))
                .when(gatewayClient).reportFailure(anyLong(), any());

        WorkerProperties workerProperties = newProperties();
        WorkerLoop loop = new WorkerLoop(gatewayClient, llamaClient, promptTemplateService,
                new com.review.worker.llama.DecoderConstraintResolver(new com.fasterxml.jackson.databind.ObjectMapper(), workerProperties),
                heartbeatScheduler, metrics, workerProperties);
        loopsToStop.add(loop);
        loop.start();

        // F-WOC-03: the loop must survive reportFailureBestEffort's unmapped exception and go on to
        // claim again -- if the old, narrow catch were still in place, this second (and any further)
        // claim() call would never happen because the worker-loop thread would already have exited.
        verify(gatewayClient, timeout(3000).atLeast(2)).claim(anyString(), anyString());
        assertThat(loop.isRunning()).as("the worker-loop thread must still be alive").isTrue();

        // The failure must still be logged/counted, not silently dropped -- best-effort, not best-effort-
        // and-invisible. WorkerLoop treats an unmapped throwable the same way it treats
        // GatewayUnavailableException: incrementGatewayErrors(), never incrementFailuresReported().
        verify(metrics, timeout(3000).times(1)).incrementGatewayErrors();
        verify(metrics, times(0)).incrementFailuresReported();

        // A LlamaException getting reported at all confirms reportFailure was genuinely reached (not
        // short-circuited some other way) before it threw.
        verify(gatewayClient, times(1)).reportFailure(org.mockito.ArgumentMatchers.eq(1L), any());
    }
}
