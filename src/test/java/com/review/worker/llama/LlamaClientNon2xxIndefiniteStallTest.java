package com.review.worker.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.error.JobFailureReason;
import com.review.worker.error.LlamaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA round (task item 5, "the single most important test in this whole branch per the threat model,
 * SOGT-03/SOGB-06"): the developer's own {@code LlamaClientNon2xxClassificationTest
 * .stalledErrorBodyDegradesToLlmErrorWithinASmallDeadlineAndNeverHangs} uses MockWebServer's
 * {@code setBodyDelay(3, SECONDS)} -- a FINITE delay after which the full body is still sent. That
 * proves the read has a shorter deadline than 3 seconds, but it never actually exercises "never closes
 * the stream, never sends more bytes" (SOGT-03's literal threat scenario) end to end, and never confirms
 * the reader thread is not left permanently blocked afterward.
 *
 * <p>This test opens a raw {@link ServerSocket}, sends a 500 status line + headers advertising a large
 * {@code Content-Length}, and then writes NOTHING further and NEVER closes the connection -- a true,
 * unbounded stall, exactly SOGT-03's scenario ("a backend that sends HTTP 500 headers and then stalls
 * the body stalls that Worker permanently" unless {@code SOGB-06}'s own deadline holds). Confirms (a)
 * classification still completes within a small deadline, degrading to {@code LLM_ERROR}, and (b) the
 * background reader thread this classification spawns does not linger blocked forever once the read is
 * abandoned (i.e. closing the stream on timeout actually unblocks the blocked {@code read()}).
 */
class LlamaClientNon2xxIndefiniteStallTest {

    private ServerSocket serverSocket;
    private volatile Socket accepted;
    private Thread serverThread;

    private void startRawStallingServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        serverThread = new Thread(() -> {
            try {
                accepted = serverSocket.accept();
                // Drain (don't bother parsing) the request so the client's write side doesn't stall too.
                accepted.getInputStream();
                OutputStream out = accepted.getOutputStream();
                out.write(("HTTP/1.1 500 Internal Server Error\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: 1000000\r\n"
                        + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // Deliberately write NOTHING further and NEVER close the socket -- an indefinite stall,
                // never a finite delay. The connection stays open until the test's tearDown forces it shut.
                Thread.sleep(60_000);
            } catch (IOException | InterruptedException ignored) {
                // Expected once tearDown closes the sockets out from under this thread.
            }
        }, "raw-stalling-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (accepted != null) {
            accepted.close();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    @Test
    void aServerThatSendsHeadersAndThenNeverWritesAnotherByteAndNeverClosesStillDegradesWithinASmallDeadline()
            throws IOException, InterruptedException {
        startRawStallingServer();
        int port = serverSocket.getLocalPort();

        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:" + port + "/");
        properties.getLlama().setModel("test-model");
        properties.getNetwork().setRequestTimeoutSec(10);

        HttpClient httpClient = HttpClient.newHttpClient();
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port + "/")
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        LlamaClient llamaClient = new LlamaClient(restClient, httpClient, new ObjectMapper(), properties);

        LlamaClient.AsyncCompletion call = llamaClient.startChatCompletion(
                java.util.List.of(new com.review.worker.llama.dto.ChatMessage("user", "review this diff")),
                "test-model", 0.1, 100);
        HttpResponse<InputStream> response = call.future().join();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> llamaClient.parseResponse(response, "test-model", 0L))
                .isInstanceOf(LlamaException.class)
                .satisfies(e -> assertThat(((LlamaException) e).getReason()).isEqualTo(JobFailureReason.LLM_ERROR));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs)
                .as("a body that NEVER arrives and NEVER closes must still degrade well within a few "
                        + "seconds, never the full request-timeout-sec (10s here, 1800s default) -- this "
                        + "is SOGB-06's actual bound, not merely a finite MockWebServer delay")
                .isLessThan(4_000L);

        // Give the classification's own reader thread a short grace period to actually exit after the
        // main thread's deadline fired and closeQuietly(body) ran -- proving the blocked read() was
        // genuinely unblocked, not merely abandoned-but-still-running forever in the background.
        boolean readerThreadStillAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("llama-error-body-read") && t.isAlive());
        if (readerThreadStillAlive) {
            Thread.sleep(500);
            readerThreadStillAlive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().equals("llama-error-body-read") && t.isAlive());
        }
        assertThat(readerThreadStillAlive)
                .as("the daemon reader thread spawned to classify the error body must not still be "
                        + "blocked in read() after the deadline fired and the stream was closed")
                .isFalse();
    }
}
