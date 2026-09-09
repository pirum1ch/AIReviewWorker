package com.review.worker.lifecycle;

import com.review.worker.core.WorkerLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code SmartLifecycle} that bounds shutdown (architecture §9): stop claiming new jobs immediately, wait
 * up to {@code spring.lifecycle.timeout-per-shutdown-phase} for the in-flight job (if any) to finish and
 * submit its result, and — if it does not finish in time — force-abandon it rather than block
 * indefinitely (an LLM generation can run tens of minutes, far beyond any sane shutdown window; the
 * Gateway reclaims an abandoned job via its own heartbeat-timeout sweep).
 *
 * <p>The value is read as a plain {@code String} and parsed with Spring Boot's own
 * {@link DurationStyle} rather than relying on {@code @Value}'s default conversion service correctly
 * resolving a {@code Duration} target type, which is not guaranteed outside of
 * {@code @ConfigurationProperties} binding.
 */
@Component
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);
    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(120);
    private static final Duration POST_ABANDON_GRACE = Duration.ofSeconds(5);

    private final WorkerLoop workerLoop;
    private final Duration shutdownGrace;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GracefulShutdown(WorkerLoop workerLoop,
                             @Value("${spring.lifecycle.timeout-per-shutdown-phase:120s}") String shutdownGraceValue) {
        this.workerLoop = workerLoop;
        this.shutdownGrace = parseGrace(shutdownGraceValue);
    }

    private static Duration parseGrace(String value) {
        try {
            return DurationStyle.detectAndParse(value);
        } catch (RuntimeException e) {
            log.warn("Could not parse spring.lifecycle.timeout-per-shutdown-phase='{}'; defaulting to {}",
                    value, DEFAULT_GRACE);
            return DEFAULT_GRACE;
        }
    }

    @Override
    public void start() {
        // WorkerRunner (an ApplicationRunner) is responsible for actually starting the worker-loop
        // thread after context refresh; this bean only coordinates the bounded stop sequence.
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Graceful shutdown: no longer claiming new jobs; waiting up to {} for any in-flight job",
                shutdownGrace);
        workerLoop.requestShutdown();
        Thread watcher = new Thread(() -> awaitAndAbandonIfNeeded(callback), "worker-shutdown");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void awaitAndAbandonIfNeeded(Runnable callback) {
        try {
            boolean finished = workerLoop.awaitTermination(shutdownGrace);
            if (!finished) {
                log.warn("Shutdown grace period ({}) elapsed with a job still in flight; abandoning it",
                        shutdownGrace);
                workerLoop.abandonCurrentJob();
                workerLoop.awaitTermination(POST_ABANDON_GRACE);
            }
        } finally {
            running.set(false);
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
