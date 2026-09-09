package com.review.worker.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Binds the whole {@code gateway/worker/backend/llama/network/heartbeat/prompt} configuration surface
 * (top-level YAML keys, architecture §4.1 — hence no common {@code prefix}; this binds at the
 * configuration root) and fails the application fast on any of the rules in architecture §4.2, as
 * amended by {@code docs/worker-threat-model.md}, which the threat model states explicitly
 * <b>override</b> the architecture doc where they differ:
 *
 * <ul>
 *   <li>WSR-01: {@code promptVersion} allowlist validation lives in {@code PromptTemplateService}, not
 *       here — but the {@code prompt.location} prefix that makes that validation meaningful (WSR-07:
 *       classpath-only, never an operator-writable directory) <em>is</em> enforced here.</li>
 *   <li>WSR-09: {@code worker.allow-insecure-gateway} is honored only when {@code gateway.url}'s host is
 *       loopback; a non-loopback host with a plain {@code http://} URL always fails startup, flag or
 *       not.</li>
 *   <li>WSR-12: the <em>effective</em> actuator bind address must resolve to a loopback address; a blank
 *       or off-loopback value fails startup. This is the one rule here that reads properties outside the
 *       {@code gateway/worker/.../prompt} tree, via direct {@code @Value} injection — and it deliberately
 *       computes which property is actually in effect (per Spring Boot's documented behavior, actuator
 *       follows {@code management.server.address} only when {@code management.server.port} is a
 *       <em>distinct</em> port from {@code server.port}; otherwise it follows {@code server.address})
 *       rather than always validating {@code management.server.address} in isolation, which would be
 *       false assurance (FW-01) whenever no distinct management port is configured (the Worker's own
 *       {@code application.yml} default).</li>
 * </ul>
 *
 * <p>Basic per-field constraints (non-blank, positive) are also declared as JSR-380 annotations
 * (enforced by {@code spring-boot-starter-validation} because this class is {@code @Validated}); the
 * cross-field/business rules that annotations cannot express (scheme + loopback exceptions, the
 * heartbeat-vs-staleness relationship, the classpath-only prompt location, the management-address
 * check) live in {@link #validateOnStartup()}, mirroring the Gateway's own
 * {@code GatewayProperties.validateOnStartup()} pattern. Every failure message names the property only,
 * never its value.
 */
@Component
@ConfigurationProperties
@Validated
public class WorkerProperties {

    private static final Logger log = LoggerFactory.getLogger(WorkerProperties.class);

    private static final int MIN_API_KEY_LENGTH = 32;
    private static final int HEARTBEAT_STALENESS_CEILING_SEC = 180;
    private static final int HEARTBEAT_WARN_THRESHOLD_SEC = 90;
    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Valid
    private final Gateway gateway = new Gateway();
    @Valid
    private final Worker worker = new Worker();
    @Valid
    private final Backend backend = new Backend();
    @Valid
    private final Llama llama = new Llama();
    @Valid
    private final Network network = new Network();
    @Valid
    private final Heartbeat heartbeat = new Heartbeat();
    @Valid
    private final Prompt prompt = new Prompt();

    /**
     * These four are read directly (not part of the {@code gateway/worker/...} tree) so
     * {@link #validateOnStartup()} can enforce WSR-12 (actuator must bind to loopback only) without
     * requiring every caller to also inject Spring Boot's own {@code ServerProperties}/
     * {@code ManagementServerProperties}. All four together determine which address is *actually*
     * effective for the actuator endpoints (FW-01: {@code management.server.address} alone is silently
     * ignored whenever no distinct {@code management.server.port} is configured).
     */
    private final String serverAddress;
    private final String serverPort;
    private final String managementServerAddress;
    private final String managementServerPort;

    public WorkerProperties(@Value("${server.address:}") String serverAddress,
                             @Value("${server.port:}") String serverPort,
                             @Value("${management.server.address:}") String managementServerAddress,
                             @Value("${management.server.port:}") String managementServerPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.managementServerAddress = managementServerAddress;
        this.managementServerPort = managementServerPort;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public Worker getWorker() {
        return worker;
    }

    public Backend getBackend() {
        return backend;
    }

    public Llama getLlama() {
        return llama;
    }

    public Network getNetwork() {
        return network;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    @PostConstruct
    void validateOnStartup() {
        validateGatewayUrl();
        validateLlamaUrl();
        requirePositive("network.pollIntervalMs", network.getPollIntervalMs());
        requirePositive("network.requestTimeoutSec", network.getRequestTimeoutSec());
        requirePositive("network.gatewayTimeoutSec", network.getGatewayTimeoutSec());
        validateHeartbeatInterval();
        requirePositive("worker.limits.maxDiffBytes", worker.getLimits().getMaxDiffBytes());
        requirePositive("worker.limits.maxResponseBytes", worker.getLimits().getMaxResponseBytes());
        requirePositive("worker.limits.maxSystemMessages", worker.getLimits().getMaxSystemMessages());
        requirePositive("worker.limits.maxConstraintBytes", worker.getLimits().getMaxConstraintBytes());
        if (worker.getLog().getIdleSummaryIntervalSec() < 0) {
            throw new IllegalStateException("worker.log.idleSummaryIntervalSec must be >= 0 (0 disables it) — refusing to start");
        }
        validatePromptLocation();
        validateServerBinding();
        warnIfHeapDumpOnOutOfMemoryEnabled();
    }

    private void validateGatewayUrl() {
        String url = gateway.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("gateway.url must be set — refusing to start");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("gateway.url is not a valid URI — refusing to start");
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("gateway.url must use http:// or https:// — refusing to start");
        }
        // scheme is plain http: only ever acceptable for a loopback host, and only with the explicit flag.
        if (!worker.isAllowInsecureGateway()) {
            throw new IllegalStateException(
                    "gateway.url must use https:// (set worker.allow-insecure-gateway=true only for a "
                            + "loopback gateway.url in dev) — refusing to start");
        }
        if (!isLoopbackHost(uri.getHost())) {
            throw new IllegalStateException(
                    "worker.allow-insecure-gateway is only valid when gateway.url's host is loopback "
                            + "(localhost/127.0.0.1/::1) — refusing to start");
        }
        log.warn("gateway.url uses plain http:// against a loopback host with worker.allow-insecure-gateway=true "
                + "— acceptable for local development only, never in production");
    }

    private void validateLlamaUrl() {
        String url = llama.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("llama.url must be set — refusing to start");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("llama.url is not a valid URI — refusing to start");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("llama.url must use http:// or https:// — refusing to start");
        }
        // WSR-06 (SHOULD): a non-loopback llama URL is a warn, not a hard fail -- the llama socket is
        // unauthenticated by design (architecture §13/threat-model WA5); non-loopback deployments are
        // unusual but not forbidden, so this is surfaced rather than silently accepted.
        if (!isLoopbackHost(uri.getHost()) && !llama.isAllowNonLoopback()) {
            log.warn("llama.url host is not loopback (127.0.0.1/::1/localhost) and llama.allow-non-loopback "
                    + "is not set — the llama-server endpoint is unauthenticated; confirm this is intentional "
                    + "(WSR-06)");
        }
    }

    private void validateHeartbeatInterval() {
        int intervalSec = heartbeat.getIntervalSec();
        if (intervalSec <= 0) {
            throw new IllegalStateException("heartbeat.intervalSec must be > 0 — refusing to start");
        }
        if (intervalSec >= HEARTBEAT_STALENESS_CEILING_SEC) {
            throw new IllegalStateException(
                    "heartbeat.intervalSec must be < " + HEARTBEAT_STALENESS_CEILING_SEC
                            + " (the Gateway's stale-heartbeat timeout) or every job would self-evict — refusing to start");
        }
        if (intervalSec > HEARTBEAT_WARN_THRESHOLD_SEC) {
            log.warn("heartbeat.intervalSec={} leaves little margin below the Gateway's {}s staleness timeout "
                    + "— consider a lower value", intervalSec, HEARTBEAT_STALENESS_CEILING_SEC);
        }
    }

    private void validatePromptLocation() {
        String location = prompt.getLocation();
        if (location == null || !location.startsWith("classpath:")) {
            // WSR-07: templates must ship only on the classpath inside the fat JAR; there must be no
            // code path (or even a reachable configuration) that resolves a template from an
            // operator-writable filesystem directory.
            throw new IllegalStateException("prompt.location must start with 'classpath:' — refusing to start");
        }
    }

    /**
     * WSR-12/FW-01: fail fast unless the actuator's <em>effective</em> bind address is loopback -- not
     * just whatever {@code management.server.address} literally says, which is silently ignored by
     * Spring Boot whenever {@code management.server.port} is unset or equal to {@code server.port} (the
     * documented behavior: management only gets its own bind address when it has its own, distinct
     * port). Validating the inert property in that case is a false-assurance anti-pattern -- it looks
     * covered in review/tests but the actuator actually binds via {@code server.address} instead, which
     * could be unset (⇒ all interfaces).
     *
     * <p>Two cases:
     * <ul>
     *   <li>No distinct management port (the Worker's own {@code application.yml} default): the actuator
     *       follows {@code server.address}, so that is what must be loopback.</li>
     *   <li>A distinct {@code management.server.port} is configured (whether via this project's own
     *       config or an operator's environment override): the actuator gets its own bind address from
     *       {@code management.server.address}, so <em>that</em> must be loopback instead.</li>
     * </ul>
     */
    private void validateServerBinding() {
        boolean distinctManagementPort = hasDistinctManagementPort();
        String effectiveAddress = distinctManagementPort ? managementServerAddress : serverAddress;
        String effectivePropertyName = distinctManagementPort ? "management.server.address" : "server.address";

        if (effectiveAddress == null || effectiveAddress.isBlank()) {
            throw new IllegalStateException(
                    effectivePropertyName + " must be set to a loopback address (127.0.0.1) — refusing to start");
        }
        if (!isLoopbackHost(effectiveAddress.trim())) {
            throw new IllegalStateException(
                    effectivePropertyName + " must be a loopback address (127.0.0.1/::1/localhost) — refusing to start");
        }
    }

    private boolean hasDistinctManagementPort() {
        if (managementServerPort == null || managementServerPort.isBlank()) {
            return false;
        }
        String normalizedServerPort = serverPort == null ? "" : serverPort.trim();
        return !managementServerPort.trim().equals(normalizedServerPort);
    }

    /**
     * Best-effort, WARN-only (not fail-fast: WSR-16 is a SHOULD, and this is a packaging/launch-flag
     * concern the Worker process cannot itself change once the JVM has already started). Confidentiality
     * of the in-memory-only diff/token relies on no heap dump ever being written to disk (WT-10).
     */
    private void warnIfHeapDumpOnOutOfMemoryEnabled() {
        try {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            boolean heapDumpEnabled = jvmArgs.stream()
                    .anyMatch(arg -> arg.toLowerCase(Locale.ROOT).contains("+heapdumponoutofmemoryerror"));
            if (heapDumpEnabled) {
                log.warn("JVM flag -XX:+HeapDumpOnOutOfMemoryError is enabled -- an OOM would write the diff/"
                        + "token-bearing heap to disk (WT-10). Launch with -XX:-HeapDumpOnOutOfMemoryError, "
                        + "or restrict -XX:HeapDumpPath to a 0700 dir on an encrypted volume.");
            }
        } catch (RuntimeException e) {
            // Never fail startup over a diagnostic-only check.
            log.debug("Could not inspect JVM input arguments for HeapDumpOnOutOfMemoryError", e);
        }
    }

    private void requirePositive(String propertyName, long value) {
        if (value <= 0) {
            throw new IllegalStateException(propertyName + " must be > 0 — refusing to start");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return LOOPBACK_HOSTS.contains(normalized) || normalized.startsWith("127.");
    }

    public static class Gateway {
        @NotBlank
        private String url;
        @NotBlank
        private String apiKey;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class Worker {
        @NotBlank
        private String id;
        private String version = "1.0.0";
        /** WSR-09: dev-only escape hatch, valid only when {@code gateway.url}'s host is loopback. */
        private boolean allowInsecureGateway = false;
        @Valid
        private final Limits limits = new Limits();
        @Valid
        private final Log log = new Log();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public boolean isAllowInsecureGateway() {
            return allowInsecureGateway;
        }

        public void setAllowInsecureGateway(boolean allowInsecureGateway) {
            this.allowInsecureGateway = allowInsecureGateway;
        }

        public Limits getLimits() {
            return limits;
        }

        public Log getLog() {
            return log;
        }

        /**
         * Worker Observability &amp; Claim Latency (WOC-04): idle-liveness log summary config.
         */
        public static class Log {
            /**
             * At most one INFO idle-summary line per this many seconds while no job is available;
             * {@code 0} disables it entirely. Default {@code 300} (5 minutes).
             */
            private int idleSummaryIntervalSec = 300;

            public int getIdleSummaryIntervalSec() {
                return idleSummaryIntervalSec;
            }

            public void setIdleSummaryIntervalSec(int idleSummaryIntervalSec) {
                this.idleSummaryIntervalSec = idleSummaryIntervalSec;
            }
        }

        /** WSR-03/WSR-04: independent Worker-side caps on Gateway-/llama-supplied data (treated as untrusted). */
        public static class Limits {
            /**
             * Hard cap on the claimed diff, in bytes. Defaulted generously above the Gateway's own
             * diff-size budget (~40,000 chars at its default {@code chars-per-token=4} /
             * {@code max-diff-tokens=10000}) so normal operation never trips it; it exists purely as a
             * defensive bound against a misbehaving/compromised Gateway or a MITM (WSR-03).
             */
            private long maxDiffBytes = 262_144L;
            /**
             * Hard cap on the llama response body, in bytes. Deliberately set to match the Gateway's own
             * documented "normal" raw-response ceiling ({@code gateway.publish.max-raw-response-length},
             * 200,000 characters) rather than an arbitrary figure, and stays safely below the Gateway's
             * true edge cap for the whole {@code POST /jobs/{id}/result} body (500,000 bytes,
             * {@code gateway.publish.max-request-body-bytes}) even after JSON-escaping overhead (WSR-04).
             */
            private long maxResponseBytes = 200_000L;
            /**
             * Prompt Manager (V3, WSR-03 sibling): independent Worker-side cap on the number of
             * {@code systemMessages} entries a claim payload may carry, regardless of what the Gateway
             * itself enforces ({@code gateway.prompt.limits.max-sections}). Combined with
             * {@link #maxDiffBytes} for the total-size check (diff + chunkContext + systemMessages).
             */
            private int maxSystemMessages = 8;
            /**
             * Structured Review Output (SRO-13, threat model SOR-06 CRITICAL): independent Worker-side
             * bound on the Gateway-supplied {@code responseFormat}/{@code jsonSchema} text, measured on
             * <b>UTF-8 bytes of the raw text before {@code readTree}</b> — never {@code String.length()},
             * never post-parse. Deliberately set above {@code gateway.structured.max-schema-bytes}
             * (65536) by at least the largest wire wrapper (~70 bytes for
             * {@code RESPONSE_FORMAT_JSON_SCHEMA}'s {@code {"type":"json_schema","json_schema":{"name":
             * "code_review","strict":true,"schema":...}}} envelope) — the two bounds measure different
             * quantities, and setting them equal would produce a fleet-wide {@code CONSTRAINT_INVALID}
             * loop at the top of the range.
             */
            private long maxConstraintBytes = 69_632L;

            public long getMaxDiffBytes() {
                return maxDiffBytes;
            }

            public void setMaxDiffBytes(long maxDiffBytes) {
                this.maxDiffBytes = maxDiffBytes;
            }

            public long getMaxResponseBytes() {
                return maxResponseBytes;
            }

            public void setMaxResponseBytes(long maxResponseBytes) {
                this.maxResponseBytes = maxResponseBytes;
            }

            public int getMaxSystemMessages() {
                return maxSystemMessages;
            }

            public void setMaxSystemMessages(int maxSystemMessages) {
                this.maxSystemMessages = maxSystemMessages;
            }

            public long getMaxConstraintBytes() {
                return maxConstraintBytes;
            }

            public void setMaxConstraintBytes(long maxConstraintBytes) {
                this.maxConstraintBytes = maxConstraintBytes;
            }
        }
    }

    public static class Backend {
        @NotBlank
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    public static class Llama {
        @NotBlank
        private String url = "http://127.0.0.1:8000";
        @NotBlank
        private String model;
        private double temperature = 0.1;
        @Positive
        private int maxTokens = 4096;
        /** WSR-06: suppresses the non-loopback warning for an intentional non-loopback deployment. */
        private boolean allowNonLoopback = false;
        /**
         * {@code true} (default) sends no {@code chat_template_kwargs} at all -- byte-identical to every
         * existing request, no behavior change without an explicit operator opt-in. {@code false} sends
         * {@code chat_template_kwargs: {"enable_thinking": false}} on every request, unconditionally
         * suppressing a reasoning model's hidden "thinking" pass (a Qwen3-family build otherwise spends
         * part of {@code max_tokens} on a hidden {@code reasoning_content} block before the actual answer,
         * which can exhaust the whole budget on a large diff and leave {@code message.content} empty --
         * surfaces as {@code LLM_EMPTY_RESPONSE} even though the backend is healthy). Prefer tuning
         * {@code reasoning_effort} via the backend's own {@code --chat-template-kwargs} startup flag
         * first (bounds thinking without removing it); this flag is the blunt, always-off fallback for a
         * backend where that isn't available. llama-server ignores the field for chat templates that
         * don't support it.
         */
        private boolean enableThinking = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public boolean isAllowNonLoopback() {
            return allowNonLoopback;
        }

        public void setAllowNonLoopback(boolean allowNonLoopback) {
            this.allowNonLoopback = allowNonLoopback;
        }

        public boolean isEnableThinking() {
            return enableThinking;
        }

        public void setEnableThinking(boolean enableThinking) {
            this.enableThinking = enableThinking;
        }
    }

    public static class Network {
        @Positive
        private long pollIntervalMs = 3000L;
        @Positive
        private int requestTimeoutSec = 1800;
        @Positive
        private int gatewayTimeoutSec = 10;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getRequestTimeoutSec() {
            return requestTimeoutSec;
        }

        public void setRequestTimeoutSec(int requestTimeoutSec) {
            this.requestTimeoutSec = requestTimeoutSec;
        }

        public int getGatewayTimeoutSec() {
            return gatewayTimeoutSec;
        }

        public void setGatewayTimeoutSec(int gatewayTimeoutSec) {
            this.gatewayTimeoutSec = gatewayTimeoutSec;
        }
    }

    public static class Heartbeat {
        private int intervalSec = 60;

        public int getIntervalSec() {
            return intervalSec;
        }

        public void setIntervalSec(int intervalSec) {
            this.intervalSec = intervalSec;
        }
    }

    public static class Prompt {
        @NotBlank
        private String location = "classpath:prompts/";

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }
}
