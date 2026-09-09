package com.review.worker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The six Worker metrics from architecture §8, registered under their dotted Micrometer names (rendered
 * by the Prometheus registry with dots replaced by underscores, Counters auto-suffixed {@code _total},
 * and this Timer's base unit set to seconds so it renders {@code _seconds_count/_sum/_max}):
 *
 * <ul>
 *   <li>{@code worker.jobs} → {@code worker_jobs_total} — every claimed job.</li>
 *   <li>{@code worker.jobs.completed} → {@code worker_jobs_completed_total} — jobs that reached a
 *       submitted result.</li>
 *   <li>{@code worker.jobs.failed} → {@code worker_jobs_failed_total} — jobs abandoned or failed.</li>
 *   <li>{@code worker.llama.duration} → {@code worker_llama_duration_seconds_*} — llama-server call
 *       latency.</li>
 *   <li>{@code worker.gateway.errors} → {@code worker_gateway_errors_total} — {@code GatewayUnavailableException}
 *       occurrences.</li>
 *   <li>{@code worker.uptime} → {@code worker_uptime_seconds} — seconds since this component was created.</li>
 * </ul>
 *
 * <p>Deliberately a plain class (not a {@code @Component}) so it is only ever constructed once, by
 * {@code MetricsConfig}, which owns the {@code @Bean} definition.
 */
public class WorkerMetrics {

    private final Counter jobsTotal;
    private final Counter jobsCompleted;
    private final Counter jobsFailed;
    private final Timer llamaDuration;
    private final Counter gatewayErrors;
    private final Counter failuresReported;

    public WorkerMetrics(MeterRegistry registry) {
        this.jobsTotal = Counter.builder("worker.jobs")
                .description("Total jobs claimed by this Worker")
                .register(registry);
        this.jobsCompleted = Counter.builder("worker.jobs.completed")
                .description("Jobs that reached a submitted result")
                .register(registry);
        this.jobsFailed = Counter.builder("worker.jobs.failed")
                .description("Jobs abandoned or failed")
                .register(registry);
        this.llamaDuration = Timer.builder("worker.llama.duration")
                .description("llama-server chat-completion call latency")
                .register(registry);
        this.gatewayErrors = Counter.builder("worker.gateway.errors")
                .description("GatewayUnavailableException occurrences")
                .register(registry);
        // WOC-39: distinguishes "failed and told the Gateway" from "failed silently" (the report is
        // best-effort, WOC-35 -- this counter, not worker.jobs.failed, is what tells an operator whether
        // the fast-recovery path is actually landing).
        this.failuresReported = Counter.builder("worker.failures.reported")
                .description("POST /jobs/{id}/fail reports the Gateway accepted (or that were at least attempted)")
                .register(registry);
        Instant startedAt = Instant.now();
        Gauge.builder("worker.uptime", startedAt, this::secondsSince)
                .description("Seconds since this Worker process started")
                .baseUnit("seconds")
                .register(registry);
    }

    private double secondsSince(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis() / 1000.0;
    }

    public void incrementJobsTotal() {
        jobsTotal.increment();
    }

    public void incrementJobsCompleted() {
        jobsCompleted.increment();
    }

    public void incrementJobsFailed() {
        jobsFailed.increment();
    }

    public void recordLlamaDuration(Duration duration) {
        llamaDuration.record(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void incrementGatewayErrors() {
        gatewayErrors.increment();
    }

    public void incrementFailuresReported() {
        failuresReported.increment();
    }
}
