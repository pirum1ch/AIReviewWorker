package com.review.worker.prompt;

import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.llama.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the WSR-01/WSR-02/WSR-03/WSR-07 requirements: {@code promptVersion} allowlisting (including
 * path-traversal-shaped injections) before any resource resolution, literal-only {@code {{DIFF}}}
 * substitution, diff-size enforcement, and template override precedence.
 *
 * <p>Uses the real {@code prompts/v1.yml} shipped on the test classpath (identical to the production
 * resource) plus a {@code prompts/with-overrides.yml} fixture added under {@code src/test/resources} to
 * exercise the override-precedence path.
 */
class PromptTemplateServiceTest {

    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        properties.getLlama().setTemperature(0.2);
        properties.getLlama().setMaxTokens(2048);
        service = new PromptTemplateService(properties);
    }

    @Test
    void resolvesV1TemplateAndSubstitutesDiffLiterally() {
        ResolvedPrompt resolved = service.resolve("v1", "diff --git a/A.java b/A.java\n+System.out.println();");

        assertThat(resolved.messages()).isNotEmpty();
        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.role()).isEqualTo("user");
        assertThat(userMessage.content()).contains("diff --git a/A.java b/A.java");
        assertThat(userMessage.content()).doesNotContain("{{DIFF}}");
    }

    @Test
    void fallsBackToLlamaConfigWhenTemplateHasNoOverrides() {
        ResolvedPrompt resolved = service.resolve("v1", "some diff");

        assertThat(resolved.model()).isEqualTo("default-model");
        assertThat(resolved.temperature()).isEqualTo(0.2);
        assertThat(resolved.maxTokens()).isEqualTo(2048);
    }

    @Test
    void templateOverridesTakePrecedenceOverLlamaConfig() {
        ResolvedPrompt resolved = service.resolve("with-overrides", "some diff");

        assertThat(resolved.model()).isEqualTo("template-model");
        assertThat(resolved.temperature()).isEqualTo(0.7);
        assertThat(resolved.maxTokens()).isEqualTo(512);
    }

    @Test
    void diffContainingLiteralBracesIsNeverReinterpretedAsAnExpression() {
        String trickyDiff = "if (x) { ${7*7} #{7*7} <#if true>oops</#if> }";
        ResolvedPrompt resolved = service.resolve("v1", trickyDiff);

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).contains(trickyDiff);
        assertThat(userMessage.content()).doesNotContain("49");
    }

    @Test
    void unknownPromptVersionIsAbandoned() {
        assertThatThrownBy(() -> service.resolve("does-not-exist", "diff"))
                .isInstanceOf(AbandonJobException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "..%2f..%2fetc%2fpasswd",
            "v1/../../../etc/passwd",
            "v1/",
            "..",
            "v1\nX-Injected: true",
            ""
    })
    void pathTraversalAndInjectionShapedPromptVersionsAreRejectedBeforeResourceResolution(String malicious) {
        assertThatThrownBy(() -> service.resolve(malicious, "diff"))
                .isInstanceOf(AbandonJobException.class);
    }

    @Test
    void nullPromptVersionIsRejected() {
        assertThatThrownBy(() -> service.resolve(null, "diff")).isInstanceOf(AbandonJobException.class);
    }

    // ---- V2 (diff chunking): chunkContext substitution ----

    @Test
    void resolvesV2TemplateAndSubstitutesChunkContextAndDiff() {
        ResolvedPrompt resolved = service.resolve("v2", "diff --git a/A.java b/A.java\n+x();", "part 2 of 5\nA.java");

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).contains("part 2 of 5");
        assertThat(userMessage.content()).contains("diff --git a/A.java b/A.java");
        assertThat(userMessage.content()).doesNotContain("{{CHUNK_CONTEXT}}");
        assertThat(userMessage.content()).doesNotContain("{{DIFF}}");
    }

    @Test
    void nullChunkContextSubstitutesToEmptyString() {
        ResolvedPrompt resolved = service.resolve("v2", "some diff", null);

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).doesNotContain("{{CHUNK_CONTEXT}}");
    }

    /**
     * CSR-08 (MUST): two sequential {@code String.replace} calls would be unsafe -- a file literally
     * named {@code {{DIFF}}} inside the chunk context, or a diff containing the literal text
     * {@code {{CHUNK_CONTEXT}}}, must resolve each placeholder EXACTLY once from the original template
     * text, with no cross-substitution in either direction.
     */
    @Test
    void placeholdersResolveExactlyOnceWithNoCrossSubstitutionEvenWhenContentContainsTheOtherPlaceholderLiterally() {
        String diffContainingChunkContextLiteral = "diff --git a/{{CHUNK_CONTEXT}}.java b/{{CHUNK_CONTEXT}}.java\n+ real diff content";
        String chunkContextContainingDiffLiteral = "part 1 of 2, file {{DIFF}}.java";

        ResolvedPrompt resolved = service.resolve("v2", diffContainingChunkContextLiteral, chunkContextContainingDiffLiteral);

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        String content = userMessage.content();

        // The diff's literal "{{CHUNK_CONTEXT}}" text is DIFF content (substituted via {{DIFF}}, not a
        // template placeholder itself) and must survive unresolved/unexpanded. The chunkContext's literal
        // "{{DIFF}}" text has its brace characters stripped by the CSR-08 defense-in-depth pass (applied
        // to chunkContext before substitution) but must NOT have been replaced with the actual diff text.
        assertThat(content).contains("diff --git a/{{CHUNK_CONTEXT}}.java b/{{CHUNK_CONTEXT}}.java");
        assertThat(content).contains("part 1 of 2, file DIFF.java");
        // The template's own two real placeholders must each have been substituted exactly once.
        assertThat(content).contains("real diff content");
        // No cross-substitution: the count of "real diff content" must be exactly 1 (not duplicated by a
        // second, accidental substitution pass), and the same for the chunk-context text.
        assertThat(countOccurrences(content, "real diff content")).isEqualTo(1);
        assertThat(countOccurrences(content, "part 1 of 2, file")).isEqualTo(1);
    }

    /** CSR-08 defense in depth: literal {@code {{}}} sequences are stripped from chunkContext before substitution. */
    @Test
    void literalBraceSequencesAreStrippedFromChunkContextBeforeSubstitution() {
        ResolvedPrompt resolved = service.resolve("v2", "a diff", "part 1 of 2 {{INJECTED}}");

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).doesNotContain("{{INJECTED}}");
        assertThat(userMessage.content()).contains("part 1 of 2 INJECTED");
    }

    /** CSR-12 (MUST): fail closed if chunkContext is supplied but the resolved template has no placeholder for it. */
    @Test
    void chunkContextSuppliedForATemplateWithNoPlaceholderIsAbandoned() {
        assertThatThrownBy(() -> service.resolve("v1", "some diff", "part 1 of 2"))
                .isInstanceOf(AbandonJobException.class);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void oversizedDiffIsAbandonedBeforeTemplateResolution() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxDiffBytes(10);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);

        assertThatThrownBy(() -> smallLimitService.resolve("v1", "a diff that is definitely too long"))
                .isInstanceOf(AbandonJobException.class);
    }

    /** Extends {@code worker.limits.max-diff-bytes} to cover {@code diff bytes + chunkContext bytes} combined. */
    @Test
    void combinedDiffAndChunkContextSizeIsBoundedTogether() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxDiffBytes(20);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);

        // Neither the diff nor the chunkContext alone exceeds 20 bytes, but their sum does.
        assertThatThrownBy(() -> smallLimitService.resolve("v2", "12345678901", "1234567890"))
                .isInstanceOf(AbandonJobException.class);
    }

    // ---- Prompt Manager (V3): systemMessages, verbatim, no substitute() (PMR-23) ----

    @Test
    void systemMessagesBecomeVerbatimChatMessagesInOrder() {
        ResolvedPrompt resolved = service.resolve("v1", "a diff", null,
                List.of("corporate base rules", "corporate review rules"));

        List<ChatMessage> systemMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        assertThat(systemMessages).hasSize(2);
        assertThat(systemMessages.get(0).content()).isEqualTo("corporate base rules");
        assertThat(systemMessages.get(1).content()).isEqualTo("corporate review rules");
    }

    @Test
    void systemMessagesReplaceTheTemplatesOwnSystemBlockEntirelyNeverDuplicated() {
        // v1's own template has a system: block (verified by the legacy-null-behavior test below); when
        // systemMessages is supplied, that template block must be entirely ignored, not appended alongside.
        ResolvedPrompt resolved = service.resolve("v1", "a diff", null, List.of("only this system message"));

        List<ChatMessage> systemMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        assertThat(systemMessages).hasSize(1);
        assertThat(systemMessages.get(0).content()).isEqualTo("only this system message");
    }

    @Test
    void systemMessagesAreNeverPassedThroughSubstituteVerbatimEvenWithDiffPlaceholderLiteralText() {
        // PMR-23: a section whose literal text is "{{DIFF}}" must arrive byte-identical -- never expanded
        // into the actual diff content, and the diff itself must not be duplicated into the system role.
        String diff = "diff --git a/A.java b/A.java\n+ real diff content";
        ResolvedPrompt resolved = service.resolve("v2", diff, null, List.of("{{DIFF}}", "{{CHUNK_CONTEXT}}", "$1", "\\"));

        List<ChatMessage> systemMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        assertThat(systemMessages).extracting(ChatMessage::content)
                .containsExactly("{{DIFF}}", "{{CHUNK_CONTEXT}}", "$1", "\\");
        // The diff must not have been duplicated into the system role via a placeholder expansion.
        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).contains("real diff content");
        String allContent = resolved.messages().stream().map(ChatMessage::content)
                .reduce("", (a, b) -> a + b);
        assertThat(countOccurrences(allContent, "real diff content")).isEqualTo(1);
    }

    /**
     * PMR-23 x CSR-08 interaction (task ask: prove the two features don't regress each other when both
     * are active on the same job): a v2, multi-chunk-context Review whose {@code systemMessages} contain
     * literal {@code {{DIFF}}}/{@code {{CHUNK_CONTEXT}}} text, at the same time as a real, non-null
     * {@code chunkContext} that itself contains a literal {@code {{DIFF}}}. Every piece must land exactly
     * where it belongs and nowhere else: chunkContext substitutes into the user message only (with its
     * own {@code {{}}} stripped first, CSR-08 defense in depth), systemMessages are wrapped byte-for-byte
     * verbatim, and neither channel leaks into the other.
     */
    @Test
    void systemMessagesStayVerbatimAndIsolatedFromChunkContextSubstitutionWhenBothArePresentTogether() {
        String diff = "diff --git a/A.java b/A.java\n+ real diff content";
        String chunkContextWithLiteralDiffPlaceholder = "part 1 of 3, file {{DIFF}}.java";
        List<String> systemMessages = List.of("{{DIFF}}", "{{CHUNK_CONTEXT}}", "rule: use {{ in code");

        ResolvedPrompt resolved = service.resolve("v2", diff, chunkContextWithLiteralDiffPlaceholder, systemMessages);

        List<ChatMessage> systemChatMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        assertThat(systemChatMessages).extracting(ChatMessage::content)
                .containsExactly("{{DIFF}}", "{{CHUNK_CONTEXT}}", "rule: use {{ in code");

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.role()).isEqualTo("user");
        // chunkContext's own {{DIFF}} literal is brace-stripped (CSR-08 defense in depth) before
        // substitution, so it must survive as plain "DIFF.java" text, NOT be expanded into the real diff.
        assertThat(userMessage.content()).contains("part 1 of 3, file DIFF.java");
        assertThat(userMessage.content()).contains("real diff content");
        // The real diff content must appear exactly once in the whole rendered payload -- not duplicated
        // via the systemMessages' "{{DIFF}}" entry (which stays literal, never substituted) nor via any
        // cross-talk between the two channels.
        String allContent = resolved.messages().stream().map(ChatMessage::content).reduce("", (a, b) -> a + b);
        assertThat(countOccurrences(allContent, "real diff content")).isEqualTo(1);
    }

    @Test
    void systemMessagesAreNotStrippedOfLiteralBraceSequencesUnlikeChunkContext() {
        // The {{ }} stripping (CSR-08 defense in depth) stays scoped to chunkContext; systemMessages are
        // verbatim, so a section containing "{{" must survive unmodified.
        ResolvedPrompt resolved = service.resolve("v1", "a diff", null, List.of("rule: never use {{ in code"));

        List<ChatMessage> systemMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        assertThat(systemMessages.get(0).content()).isEqualTo("rule: never use {{ in code");
    }

    // ---- PMR-24: null vs [] semantics, no NPE either way ----

    @Test
    void nullSystemMessagesFallsBackToTheTemplatesOwnSystemBlockLegacyBehavior() {
        ResolvedPrompt resolved = service.resolve("v1", "a diff", null, null);

        List<ChatMessage> systemMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        // v1's template.yml ships its own system: block -- exactly today's behavior, no NPE.
        assertThat(systemMessages).hasSize(1);
    }

    @Test
    void emptySystemMessagesListProducesNoSystemChatMessageAtAll() {
        // Distinct from null: an empty list means "the Gateway resolved zero sections", not "use the
        // template's own block" -- template.system() must still be ignored (empty list is non-null).
        ResolvedPrompt resolved = service.resolve("v1", "a diff", null, List.of());

        List<ChatMessage> systemMessages = resolved.messages().stream()
                .filter(m -> "system".equals(m.role())).toList();
        assertThat(systemMessages).isEmpty();
        // The user message is still present -- no NPE, no dropped job.
        assertThat(resolved.messages()).anyMatch(m -> "user".equals(m.role()));
    }

    @Test
    void threeArgOverloadStillDelegatesWithNullSystemMessages() {
        ResolvedPrompt resolved = service.resolve("v1", "a diff", null);

        assertThat(resolved.messages().stream().filter(m -> "system".equals(m.role()))).hasSize(1);
    }

    @Test
    void twoArgOverloadStillDelegatesWithNullSystemMessages() {
        ResolvedPrompt resolved = service.resolve("v1", "a diff");

        assertThat(resolved.messages().stream().filter(m -> "system".equals(m.role()))).hasSize(1);
    }

    // ---- WSR-03 sibling: independent Worker-side systemMessages limits ----

    @Test
    void systemMessagesCountAboveMaxSystemMessagesIsAbandoned() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxSystemMessages(2);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);

        assertThatThrownBy(() -> smallLimitService.resolve("v1", "a diff", null, List.of("a", "b", "c")))
                .isInstanceOf(AbandonJobException.class);
    }

    @Test
    void systemMessagesTotalBytesCombinedWithDiffBytesIsBounded() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxDiffBytes(20);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);

        // Neither the diff nor the systemMessages alone exceeds 20 bytes, but their sum does.
        assertThatThrownBy(() -> smallLimitService.resolve("v1", "1234567890", null, List.of("1234567890123")))
                .isInstanceOf(AbandonJobException.class);
    }

    @Test
    void oversizedSystemMessagesAreAbandonedNotOom() {
        // "An oversized section is abandoned, not OOM'd" -- resolve() must reject before ever handing
        // the payload to the LLM client, never attempt to process/allocate around it further.
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxDiffBytes(100);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);
        String oversizedSection = "x".repeat(1000);

        assertThatThrownBy(() -> smallLimitService.resolve("v1", "small diff", null, List.of(oversizedSection)))
                .isInstanceOf(AbandonJobException.class);
    }

    // ---- Structured Review Output: v3.yml (SRO-60) ----

    @Test
    void v3TemplateResolvesWithItsOwnMaxTokens() {
        // Was pinned to the shipped default (8192); v3.yml's maxTokens has since been raised to 12000
        // (live-tuned to keep pace with gateway.diff.answer-reserve, which was also raised to 12000 --
        // see DEPLOYMENT.md's "Бюджет LLM-токенов" table). Still a literal, still must be updated by
        // hand if v3.yml's maxTokens changes again -- there is no dynamic cross-check.
        ResolvedPrompt resolved = service.resolve("v3", "diff --git a/A.java b/A.java\n+x();",
                "<<<FILES_IN_THIS_PART>>>\nA.java\n<<<END_FILES_IN_THIS_PART>>>\n");

        assertThat(resolved.maxTokens()).isEqualTo(12000);
        assertThat(resolved.messages()).hasSize(2);
        String userMessage = resolved.messages().get(1).content();
        assertThat(userMessage).contains("A.java");
        assertThat(userMessage).contains("diff --git a/A.java");
    }

    @Test
    void v3TemplateRequiresTheChunkContextPlaceholderSoAnAbsentPlaceholderWouldAbandon() {
        // CSR-12: a chunkContext supplied but no {{CHUNK_CONTEXT}} placeholder in the template must
        // abandon the job -- this pins that v3.yml genuinely contains the placeholder (SRO-64a depends
        // on it: the Gateway now sends a non-null chunkContext even for single-chunk structured jobs).
        ResolvedPrompt resolved = service.resolve("v3", "some diff", "coverage list here");

        assertThat(resolved.messages().get(1).content()).contains("coverage list here");
    }

    @Test
    void v3TemplateNeverModifiesV1OrV2Behavior() {
        // §8 backward-compat guarantee: introducing v3.yml must not change v1/v2 resolution at all.
        ResolvedPrompt v1 = service.resolve("v1", "diff content", null);
        assertThat(v1.maxTokens()).isNotEqualTo(8192);
    }
}
