package com.review.worker.lifecycle;

import com.review.worker.core.WorkerLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Starts the {@code worker-loop} thread once the application context has finished starting up. */
@Component
public class WorkerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    private final WorkerLoop workerLoop;

    public WorkerRunner(WorkerLoop workerLoop) {
        this.workerLoop = workerLoop;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting worker-loop");
        workerLoop.start();
    }
}
