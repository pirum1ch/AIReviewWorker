package com.review.worker.config;

import com.review.worker.metrics.WorkerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the single {@link WorkerMetrics} bean against the auto-configured {@link MeterRegistry}. */
@Configuration
public class MetricsConfig {

    @Bean
    public WorkerMetrics workerMetrics(MeterRegistry registry) {
        return new WorkerMetrics(registry);
    }
}
