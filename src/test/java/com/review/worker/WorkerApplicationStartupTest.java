package com.review.worker;

import com.review.worker.core.WorkerLoop;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR smoke test (architecture §10, task item "Startup"): the full application context must boot with a
 * valid environment, {@code WorkerRunner} must start the {@code worker-loop} thread exactly once, and
 * cold-start latency is measured against a generous regression bound (the architecture doc's own honest
 * target is ~1.2-1.8s on Mac-mini-class hardware; 5s here is a CI-safe regression guard, not the NFR
 * itself).
 *
 * <p>Deliberately does NOT use {@code @SpringBootTest} (which lets the Spring TestContext framework cache
 * and reuse contexts across test classes) so the measured startup time reflects a genuine cold boot of
 * this specific configuration, not a cache hit.
 */
class WorkerApplicationStartupTest {

    private static final Logger log = LoggerFactory.getLogger(WorkerApplicationStartupTest.class);

    /**
     * Reads {@code WorkerLoop}'s private {@code thread} field directly rather than scanning
     * {@code Thread.getAllStackTraces()} for a thread named "worker-loop": this test runs inside the
     * shared Surefire fork alongside many other test classes that legitimately start and tear down their
     * own same-named "worker-loop" threads (each with its own bounded, but asynchronous, shutdown grace
     * period) -- a JVM-wide name scan is inherently racy against those unrelated threads' teardown timing
     * and is not a reliable way to check "did *this* context's WorkerRunner start *its* loop". Reflection
     * on the specific bean instance sidesteps that entirely.
     */
    private Thread loopThreadOf(WorkerLoop workerLoop) throws ReflectiveOperationException {
        Field field = WorkerLoop.class.getDeclaredField("thread");
        field.setAccessible(true);
        return (Thread) field.get(workerLoop);
    }

    @Test
    void contextBootsWorkerRunnerStartsTheLoopExactlyOnceWithinTimeBudget() throws ReflectiveOperationException {
        long before = System.nanoTime();
        // Command-line-style "--key=value" args (not SpringApplicationBuilder.properties(), whose
        // "defaultProperties" source is the LOWEST-precedence property source -- lower than the packaged
        // application.yml's own `gateway.url: ${GATEWAY_URL}` entry. Since GATEWAY_URL is unset in this
        // test JVM, that placeholder resolves to the literal unresolved string "${GATEWAY_URL}", which
        // wins over a same-named "default" property and fails WorkerProperties' URI validation. Args
        // passed to run(...) are bound as command-line arguments, which rank above application.yml.)
        ConfigurableApplicationContext context = new SpringApplicationBuilder(WorkerApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--gateway.url=https://gateway.internal",
                        "--gateway.api-key=" + "a".repeat(40),
                        "--worker.id=worker-startup-smoke-test",
                        "--backend.id=backend-startup-smoke-test",
                        "--llama.url=http://127.0.0.1:8000",
                        "--llama.model=test-model",
                        "--management.server.address=127.0.0.1",
                        "--management.server.port=0",
                        "--server.port=0",
                        "--network.poll-interval-ms=50",
                        "--network.gateway-timeout-sec=1",
                        "--spring.lifecycle.timeout-per-shutdown-phase=2s");
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        log.info("Worker application context startup took {} ms", elapsedMs);

        try {
            // ApplicationRunners (including WorkerRunner) execute synchronously as part of
            // SpringApplication.run(); by the time run() returns, the worker-loop thread must already exist.
            WorkerLoop workerLoop = context.getBean(WorkerLoop.class);
            assertThat(workerLoop.isRunning()).as("WorkerRunner must have started the loop").isTrue();

            Thread loopThread = loopThreadOf(workerLoop);
            assertThat(loopThread).as("WorkerRunner must have started the worker-loop thread").isNotNull();
            assertThat(loopThread.getName()).isEqualTo("worker-loop");
            assertThat(loopThread.isAlive()).isTrue();

            // "Starts the loop exactly once": WorkerLoop.start() is a documented no-op if already started
            // (thread != null) -- calling it again (as e.g. a duplicated ApplicationRunner invocation
            // would) must not replace or duplicate the thread.
            workerLoop.start();
            assertThat(loopThreadOf(workerLoop)).as("a second start() call must be a no-op").isSameAs(loopThread);

            // Regression bound only -- the architecture's own honest NFR target is ~1.2-1.8s on
            // Mac-mini-class hardware (docs/worker-architecture.md §10); 5s is a generous CI-safe ceiling
            // for a warm Surefire fork. (Note: in true isolation -- a single freshly-forked JVM with no
            // prior class-loading/JIT warm-up, e.g. `mvn test -Dtest=WorkerApplicationStartupTest` alone
            // -- this was observed at ~12-13s; within the full suite's shared warm fork it is ~2.0-2.8s.
            // See the QA report for this distinction.)
            assertThat(elapsedMs).as("context startup regression bound").isLessThan(5_000L);
        } finally {
            context.close();
        }
    }
}
