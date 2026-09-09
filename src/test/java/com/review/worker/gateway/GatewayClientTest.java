package com.review.worker.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.review.worker.error.GatewayUnavailableException;
import com.review.worker.gateway.dto.ResultRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the Gateway-facing status-code contract (200/204/403/404/5xx) and confirms the bearer token
 * is attached to every request, per {@code docs/worker-threat-model.md} WSR-10 (never log the token/
 * diff/rawResponse) and the architecture's {@code /jobs/*} mapping table.
 */
class GatewayClientTest {

    private static final String BEARER_TOKEN = "s3cr3t-bearer-token-value";

    private MockRestServiceServer mockServer;
    private GatewayClient gatewayClient;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://gateway.test")
                .defaultHeader("Authorization", "Bearer " + BEARER_TOKEN);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        gatewayClient = new GatewayClient(builder.build());

        logAppender = new ListAppender<>();
        logAppender.start();
        Logger rootLogger = (Logger) LoggerFactory.getLogger(GatewayClient.class);
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(GatewayClient.class)).detachAppender(logAppender);
    }

    @Test
    void claimReturnsJobOn200() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
                .andRespond(withSuccess("""
                        {"jobId":42,"reviewId":7,"payload":{"diff":"diff content","promptVersion":"v1"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<com.review.worker.gateway.dto.ClaimResponse> result = gatewayClient.claim("backend-1", "worker-1");

        assertThat(result).isPresent();
        assertThat(result.get().jobId()).isEqualTo(42L);
        assertThat(result.get().reviewId()).isEqualTo(7L);
        assertThat(result.get().payload().promptVersion()).isEqualTo("v1");
        mockServer.verify();
    }

    // ---- Prompt Manager (V3): systemMessages / forward-compat (PMR-24) ----

    @Test
    void claimResponseWithSystemMessagesArrayParsesCorrectly() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withSuccess("""
                        {"jobId":42,"reviewId":7,"payload":{"diff":"diff content","promptVersion":"v2",
                         "systemMessages":["corporate base","corporate rules"]}}
                        """, MediaType.APPLICATION_JSON));

        Optional<com.review.worker.gateway.dto.ClaimResponse> result = gatewayClient.claim("backend-1", "worker-1");

        assertThat(result).isPresent();
        assertThat(result.get().payload().systemMessages()).containsExactly("corporate base", "corporate rules");
    }

    @Test
    void claimResponseWithSystemMessagesAbsentFromJsonDeserializesAsNullNotEmpty() {
        // PMR-24: null vs [] are distinct semantics -- when the field is entirely absent (an old
        // Gateway, or a legacy/kill-switch-off Review), Jackson must leave it null, not default to [].
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withSuccess("""
                        {"jobId":42,"reviewId":7,"payload":{"diff":"diff content","promptVersion":"v1"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<com.review.worker.gateway.dto.ClaimResponse> result = gatewayClient.claim("backend-1", "worker-1");

        assertThat(result).isPresent();
        assertThat(result.get().payload().systemMessages()).isNull();
    }

    @Test
    void claimResponseWithEmptySystemMessagesArrayDeserializesAsEmptyListNotNull() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withSuccess("""
                        {"jobId":42,"reviewId":7,"payload":{"diff":"diff content","promptVersion":"v1",
                         "systemMessages":[]}}
                        """, MediaType.APPLICATION_JSON));

        Optional<com.review.worker.gateway.dto.ClaimResponse> result = gatewayClient.claim("backend-1", "worker-1");

        assertThat(result).isPresent();
        assertThat(result.get().payload().systemMessages()).isNotNull().isEmpty();
    }

    @Test
    void claimResponseWithUnknownFieldsFromANewerGatewayIsToleratedNotRejected() {
        // PMT-05/PMR-24: an old Worker talking to a newer Gateway that adds fields must not fail
        // deserialization -- @JsonIgnoreProperties(ignoreUnknown = true) makes this a stated contract.
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withSuccess("""
                        {"jobId":42,"reviewId":7,"someBrandNewTopLevelField":"ignored",
                         "payload":{"diff":"diff content","promptVersion":"v1",
                         "someBrandNewPayloadField":{"nested":true}}}
                        """, MediaType.APPLICATION_JSON));

        Optional<com.review.worker.gateway.dto.ClaimResponse> result = gatewayClient.claim("backend-1", "worker-1");

        assertThat(result).isPresent();
        assertThat(result.get().jobId()).isEqualTo(42L);
    }

    @Test
    void claimReturnsEmptyOn204() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withNoContent());

        Optional<com.review.worker.gateway.dto.ClaimResponse> result = gatewayClient.claim("backend-1", "worker-1");

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void claimThrowsGatewayUnavailableOn5xx() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gatewayClient.claim("backend-1", "worker-1"))
                .isInstanceOf(GatewayUnavailableException.class);
        mockServer.verify();
    }

    @Test
    void heartbeatReturnsAcceptedWithShouldContinueOn200() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/heartbeat"))
                .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
                .andRespond(withSuccess("{\"shouldContinue\":true}", MediaType.APPLICATION_JSON));

        HeartbeatOutcome outcome = gatewayClient.heartbeat(42, "worker-1");

        assertThat(outcome.status()).isEqualTo(HeartbeatOutcome.HeartbeatStatus.ACCEPTED);
        assertThat(outcome.shouldContinue()).isTrue();
        mockServer.verify();
    }

    @Test
    void heartbeatReturnsNotFoundOn404() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/heartbeat"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        HeartbeatOutcome outcome = gatewayClient.heartbeat(42, "worker-1");

        assertThat(outcome.status()).isEqualTo(HeartbeatOutcome.HeartbeatStatus.NOT_FOUND);
        mockServer.verify();
    }

    @Test
    void heartbeatReturnsForbiddenOn403() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/heartbeat"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        HeartbeatOutcome outcome = gatewayClient.heartbeat(42, "worker-1");

        assertThat(outcome.status()).isEqualTo(HeartbeatOutcome.HeartbeatStatus.FORBIDDEN);
        mockServer.verify();
    }

    @Test
    void heartbeatThrowsGatewayUnavailableOn5xx() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/heartbeat"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gatewayClient.heartbeat(42, "worker-1"))
                .isInstanceOf(GatewayUnavailableException.class);
        mockServer.verify();
    }

    @Test
    void submitResultReturnsAcceptedOn200() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/result"))
                .andExpect(header("Authorization", "Bearer " + BEARER_TOKEN))
                .andRespond(withSuccess("{\"reviewId\":7,\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        ResultOutcome outcome = gatewayClient.submitResult(42,
                new ResultRequest("worker-1", "raw model output", 100, 200, 5000L, "model-x", null));

        assertThat(outcome.status()).isEqualTo(ResultOutcome.ResultStatus.ACCEPTED);
        assertThat(outcome.response().reviewId()).isEqualTo(7L);
        assertThat(outcome.response().status()).isEqualTo("COMPLETED");
        mockServer.verify();
    }

    @Test
    void submitResultReturnsNotFoundOn404() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/result"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        ResultOutcome outcome = gatewayClient.submitResult(42,
                new ResultRequest("worker-1", "raw", 1, 1, 1L, "m", null));

        assertThat(outcome.status()).isEqualTo(ResultOutcome.ResultStatus.NOT_FOUND);
        mockServer.verify();
    }

    @Test
    void submitResultReturnsForbiddenOn403() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/result"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        ResultOutcome outcome = gatewayClient.submitResult(42,
                new ResultRequest("worker-1", "raw", 1, 1, 1L, "m", null));

        assertThat(outcome.status()).isEqualTo(ResultOutcome.ResultStatus.FORBIDDEN);
        mockServer.verify();
    }

    @Test
    void submitResultThrowsGatewayUnavailableOn5xx() {
        mockServer.expect(requestTo("https://gateway.test/jobs/42/result"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gatewayClient.submitResult(42, new ResultRequest("worker-1", "raw", 1, 1, 1L, "m", null)))
                .isInstanceOf(GatewayUnavailableException.class);
        mockServer.verify();
    }

    @Test
    void noLogEventEverContainsTheBearerTokenOrPayloadContent() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andRespond(withSuccess("""
                        {"jobId":1,"reviewId":1,"payload":{"diff":"THE-SECRET-DIFF","promptVersion":"v1"}}
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://gateway.test/jobs/1/result"))
                .andRespond(withSuccess("{\"reviewId\":1,\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        gatewayClient.claim("backend-1", "worker-1");
        gatewayClient.submitResult(1, new ResultRequest("worker-1", "THE-SECRET-RAW-RESPONSE", 1, 1, 1L, "m", null));

        List<String> allMessages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(allMessages).noneMatch(msg -> msg.contains(BEARER_TOKEN));
        assertThat(allMessages).noneMatch(msg -> msg.contains("THE-SECRET-DIFF"));
        assertThat(allMessages).noneMatch(msg -> msg.contains("THE-SECRET-RAW-RESPONSE"));
        assertThat(logAppender.list).allSatisfy(event -> assertThat(event.getLevel()).isNotEqualTo(Level.ERROR));
    }
}
