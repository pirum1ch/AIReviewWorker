package com.review.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the LLM Worker: a stateless HTTP client that claims jobs from the Review Gateway,
 * calls a local llama-server, sends heartbeats, and submits results. It owns no persistent state and no
 * business logic — see {@code docs/worker-architecture.md} for the full design.
 *
 * <p>Branch 1 wires configuration, the Gateway/llama HTTP clients, prompt templating, and metrics; the
 * polling/claim loop itself ({@code core/WorkerLoop}) is Branch 2 scope and is intentionally absent here.
 */
@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
