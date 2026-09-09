package com.review.worker.prompt;

import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.llama.dto.ChatMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a Gateway-supplied {@code promptVersion} + diff (+ optional {@code chunkContext}, V2 diff
 * chunking) into the exact chat messages and model parameters to send to llama-server.
 *
 * <p>{@code promptVersion}, {@code diff}, and {@code chunkContext} are all untrusted, Gateway-supplied
 * input (the Gateway itself only forwards what GitLab CI submitted, or — for {@code chunkContext} —
 * derived from MR-author-controlled file names) and are treated accordingly:
 * <ul>
 *   <li>WSR-01: {@code promptVersion} is checked against an allowlist regex <em>before</em> it is used
 *       to build any resource path — structurally excluding path traversal, since {@code '/'} is not in
 *       the allowed character class.</li>
 *   <li>WSR-07: templates are resolved only via {@link ClassPathResource}, i.e. only from inside the fat
 *       JAR — {@code prompt.location} is validated at startup ({@code WorkerProperties}) to start with
 *       {@code "classpath:"}, so there is no reachable code path to an operator-writable directory.</li>
 *   <li>WSR-02/CSR-08: substitution is a <b>single regex pass</b> over {@code \{\{(DIFF|CHUNK_CONTEXT)\}\}}
 *       using {@code Matcher.appendReplacement}/{@code Matcher.quoteReplacement} — never re-parsed as a
 *       template/expression engine, and never re-scanned after substitution. Two sequential
 *       {@code String.replace} calls (one per placeholder) would be unsafe: {@code replace} re-scans its
 *       own output, so a file literally named {@code {{DIFF}}} inside {@code chunkContext}, or a diff
 *       containing the literal text {@code {{CHUNK_CONTEXT}}}, would cause cross-substitution. The
 *       single-pass {@link Matcher} approach replaces each placeholder exactly once, from the original
 *       template text only.</li>
 *   <li>CSR-08 (defense in depth): literal {@code {{}}} sequences are stripped from the
 *       Gateway-rendered {@code chunkContext} before it is ever substituted in.</li>
 *   <li>CSR-12: if {@code chunkContext != null} but the resolved template has no
 *       {@code {{CHUNK_CONTEXT}}} placeholder at all, the job is abandoned rather than silently
 *       proceeding without the guardrail (architecture D6).</li>
 *   <li>WSR-03: the combined diff + chunkContext size is bounded independently of whatever the
 *       Gateway itself enforces.</li>
 * </ul>
 */
@Component
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private static final Pattern PROMPT_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(DIFF|CHUNK_CONTEXT)\\}\\}");
    private static final String DIFF_TOKEN = "DIFF";
    private static final String CHUNK_CONTEXT_PLACEHOLDER = "{{CHUNK_CONTEXT}}";
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final WorkerProperties properties;

    public PromptTemplateService(WorkerProperties properties) {
        this.properties = properties;
    }

    /**
     * chore/config-consolidation: symmetric counterpart to
     * {@code GatewayProperties.validateStructuredOnStartup}'s success-path budget log on the Gateway
     * side — this only LOGS (never throws, never blocks startup on a single bad template file), since
     * templates are plain classpath resources, not a validated {@code @ConfigurationProperties} bean.
     * One INFO line per template found under {@code prompt.location} (default {@code classpath:prompts/}),
     * each showing its effective {@code maxTokens} (its own override, or the {@code llama.max-tokens}
     * global default it will fall back to) — so an operator tuning the LLM token budget can see both
     * halves (Gateway's {@code gateway.structured.answer-reserve}/{@code gateway.diff.answer-reserve}
     * and Worker's resolved per-template {@code maxTokens}) without needing to read either source file.
     * See DEPLOYMENT.md, "Бюджет LLM-токенов: сводная таблица", for the full cross-process picture.
     */
    @PostConstruct
    void logResolvedTemplateBudgetsOnStartup() {
        int globalMaxTokens = properties.getLlama().getMaxTokens();
        List<Resource> templates = listTemplateResources();
        if (templates.isEmpty()) {
            log.warn("No prompt templates found under {} at startup -- every claimed job will fail with "
                    + "'Unknown promptVersion'", properties.getPrompt().getLocation());
            return;
        }
        for (Resource resource : templates) {
            String versionName = stripYamlExtension(resource.getFilename());
            try {
                Map<String, Object> parsed = parseYamlBestEffort(resource);
                Integer templateMaxTokens = asInteger(parsed.get("maxTokens"));
                int effective = templateMaxTokens != null ? templateMaxTokens : globalMaxTokens;
                log.info("Prompt template '{}': effective maxTokens={}{}", versionName, effective,
                        templateMaxTokens != null ? "" : " (llama.max-tokens default, no per-template override)");
            } catch (RuntimeException e) {
                // Best-effort diagnostic only -- a template that fails to parse here will fail the same
                // way (as an AbandonJobException) the first time a job actually requests it; this method
                // must never turn that into a startup failure for every OTHER, valid template.
                log.warn("Prompt template '{}' could not be inspected for its budget log line ({}); it will "
                        + "still be validated normally when a job actually requests it", versionName,
                        e.getClass().getSimpleName());
            }
        }
    }

    private List<Resource> listTemplateResources() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] found = resolver.getResources(properties.getPrompt().getLocation() + "*.yml");
            List<Resource> sorted = new ArrayList<>(List.of(found));
            sorted.sort(Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
            return sorted;
        } catch (IOException e) {
            log.warn("Could not list prompt templates under {} for the startup budget log ({})",
                    properties.getPrompt().getLocation(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    private String stripYamlExtension(String filename) {
        if (filename == null) {
            return "?";
        }
        return filename.endsWith(".yml") ? filename.substring(0, filename.length() - 4) : filename;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYamlBestEffort(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read prompt template resource", e);
        }
    }

    /**
     * Prompt Manager (V3, architecture §8): {@code systemMessages} — when non-{@code null} — become
     * {@code ChatMessage("system", ...)} entries <b>verbatim</b>, in list order, never passed through
     * {@link #substitute}: they are Gateway-assembled, already-final text (PMR-23), not a template to be
     * filled in, and the {@code {{DIFF}}}/{@code {{CHUNK_CONTEXT}}} single-pass substitution stays scoped
     * to the {@code user} template exactly as before. {@code template.system()} is ignored entirely in
     * that case (never duplicated alongside {@code systemMessages}).
     *
     * <p>{@code systemMessages == null} is the explicit, tested legacy/compat branch — today's behavior
     * (the template's own {@code system:} block, if any) — used for a {@code prompt_bundle_mode=NONE}
     * Review, a Gateway with Prompt Manager disabled, or an old Gateway that doesn't send the field at
     * all (the field is optional in {@code ClaimResponse}/{@code JobPayload} — WSR/PMR forward
     * compatibility). This is <b>not</b> a fallback triggered by any error condition here. An empty list
     * ({@code List.of()}) is treated the same as {@code null} for rendering purposes (zero system
     * messages either way) but is a materially different signal upstream (Gateway resolved a Review with
     * zero sections) — this method itself does not need to distinguish the two beyond "iterate what's
     * there".
     *
     * @throws AbandonJobException if {@code promptVersion} fails the allowlist check, no matching
     *                              template exists on the classpath, the template is malformed, the
     *                              combined diff/chunkContext/systemMessages size exceeds
     *                              {@code worker.limits.max-diff-bytes}, {@code systemMessages} exceeds
     *                              {@code worker.limits.max-system-messages}, or {@code chunkContext} was
     *                              supplied but the template has no {@code {{CHUNK_CONTEXT}}}
     *                              placeholder (CSR-12).
     */
    public ResolvedPrompt resolve(String promptVersion, String diff, String chunkContext, List<String> systemMessages) {
        validatePromptVersion(promptVersion);
        validateDiffSize(diff, chunkContext, systemMessages);
        PromptTemplate template = loadTemplate(promptVersion);
        if (chunkContext != null && !hasChunkContextPlaceholder(template)) {
            throw new AbandonJobException(
                    "chunkContext was supplied but promptVersion '" + promptVersion + "' has no {{CHUNK_CONTEXT}} placeholder");
        }
        // CSR-08 defense in depth: strip literal '{{'/'}}' from the Gateway-rendered chunkContext
        // before it is ever substituted in, on top of the single-pass substitution below.
        String sanitizedChunkContext = chunkContext == null ? null : chunkContext.replace("{{", "").replace("}}", "");
        List<ChatMessage> messages = buildMessages(template, diff, sanitizedChunkContext, systemMessages);
        String model = template.model() != null ? template.model() : properties.getLlama().getModel();
        double temperature = template.temperature() != null
                ? template.temperature() : properties.getLlama().getTemperature();
        int maxTokens = template.maxTokens() != null
                ? template.maxTokens() : properties.getLlama().getMaxTokens();
        return new ResolvedPrompt(messages, model, temperature, maxTokens);
    }

    /** Backward-compatible overload: no {@code systemMessages} (legacy/kill-switch-off behavior). */
    public ResolvedPrompt resolve(String promptVersion, String diff, String chunkContext) {
        return resolve(promptVersion, diff, chunkContext, null);
    }

    /** Backward-compatible overload for callers with no chunk context (single-chunk Reviews). */
    public ResolvedPrompt resolve(String promptVersion, String diff) {
        return resolve(promptVersion, diff, null, null);
    }

    private boolean hasChunkContextPlaceholder(PromptTemplate template) {
        return (template.system() != null && template.system().contains(CHUNK_CONTEXT_PLACEHOLDER))
                || (template.user() != null && template.user().contains(CHUNK_CONTEXT_PLACEHOLDER));
    }

    private void validatePromptVersion(String promptVersion) {
        boolean valid = promptVersion != null
                && PROMPT_VERSION_PATTERN.matcher(promptVersion).matches()
                && !promptVersion.contains("..");
        if (!valid) {
            // The raw value is untrusted (possibly attacker-controlled) and must never be logged
            // verbatim (WSR-18, log-injection risk); only its length is safe to report.
            log.warn("Rejected promptVersion (length={})", promptVersion == null ? 0 : promptVersion.length());
            throw new AbandonJobException("Unknown or invalid promptVersion");
        }
    }

    /**
     * Extends {@code worker.limits.max-diff-bytes} to cover {@code diff bytes + chunkContext bytes +
     * systemMessages bytes} combined (Prompt Manager, V3). {@code systemMessages}' element count is
     * independently capped at {@code worker.limits.max-system-messages} (WSR-03 sibling: enforced here
     * regardless of what the Gateway itself enforces via {@code gateway.prompt.limits.max-sections}).
     */
    private void validateDiffSize(String diff, String chunkContext, List<String> systemMessages) {
        int diffBytes = diff == null ? 0 : diff.getBytes(StandardCharsets.UTF_8).length;
        int contextBytes = chunkContext == null ? 0 : chunkContext.getBytes(StandardCharsets.UTF_8).length;
        long systemMessagesBytes = 0;
        if (systemMessages != null) {
            int maxSystemMessages = properties.getWorker().getLimits().getMaxSystemMessages();
            if (systemMessages.size() > maxSystemMessages) {
                throw new AbandonJobException(
                        "systemMessages count exceeds worker.limits.max-system-messages (" + maxSystemMessages + ")");
            }
            for (String message : systemMessages) {
                systemMessagesBytes += message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        long combinedBytes = (long) diffBytes + contextBytes + systemMessagesBytes;
        long maxDiffBytes = properties.getWorker().getLimits().getMaxDiffBytes();
        if (combinedBytes > maxDiffBytes) {
            throw new AbandonJobException(
                    "diff + chunkContext + systemMessages exceeds worker.limits.max-diff-bytes (" + maxDiffBytes + ")");
        }
    }

    private PromptTemplate loadTemplate(String promptVersion) {
        String location = properties.getPrompt().getLocation(); // guaranteed to start with "classpath:"
        String classpathPath = location.substring(CLASSPATH_PREFIX.length()) + promptVersion + ".yml";
        ClassPathResource resource = new ClassPathResource(classpathPath);
        if (!resource.exists()) {
            throw new AbandonJobException("Unknown promptVersion: no matching template on classpath");
        }
        Map<String, Object> parsed = parseYaml(resource, promptVersion);
        String user = asString(parsed.get("user"));
        if (user == null || user.isBlank()) {
            throw new AbandonJobException("Prompt template '" + promptVersion + "' has no 'user' section");
        }
        String system = asString(parsed.get("system"));
        String model = asString(parsed.get("model"));
        Double temperature = asDouble(parsed.get("temperature"));
        Integer maxTokens = asInteger(parsed.get("maxTokens"));
        return new PromptTemplate(system, user, model, temperature, maxTokens);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(ClassPathResource resource, String promptVersion) {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new AbandonJobException("Prompt template '" + promptVersion + "' is not a YAML mapping");
            }
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new AbandonJobException("Could not read prompt template '" + promptVersion + "'", e);
        }
    }

    /**
     * PMR-23: when {@code systemMessages != null}, each element becomes its own {@code ChatMessage}
     * verbatim (no {@link #substitute}, no {@code {{}}} stripping — that stripping stays scoped to
     * {@code chunkContext}) and {@code template.system()} is ignored entirely, never duplicated
     * alongside them. {@code systemMessages == null} is exactly today's behavior.
     */
    private List<ChatMessage> buildMessages(PromptTemplate template, String diff, String chunkContext,
                                             List<String> systemMessages) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemMessages != null) {
            for (String systemMessage : systemMessages) {
                if (systemMessage != null) {
                    messages.add(new ChatMessage("system", systemMessage));
                }
            }
        } else if (template.system() != null && !template.system().isBlank()) {
            messages.add(new ChatMessage("system", substitute(template.system(), diff, chunkContext)));
        }
        messages.add(new ChatMessage("user", substitute(template.user(), diff, chunkContext)));
        return messages;
    }

    /**
     * WSR-02/CSR-08: single regex pass over {@code \{\{(DIFF|CHUNK_CONTEXT)\}\}}, replacing each match
     * exactly once from the original template text — deliberately NOT two sequential
     * {@code String.replace} calls (which would re-scan already-substituted output, letting one
     * placeholder's replacement content accidentally satisfy the other placeholder's pattern) and NOT
     * re-parsed by any expression/template engine.
     */
    private String substitute(String templateText, String diff, String chunkContext) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(templateText);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = DIFF_TOKEN.equals(token)
                    ? (diff == null ? "" : diff)
                    : (chunkContext == null ? "" : chunkContext);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
