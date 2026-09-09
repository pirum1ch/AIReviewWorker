package com.review.worker.llama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.error.JobFailureReason;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Structured Review Output (architecture §3.3, threat model SOR-06/SOR-07, both CRITICAL): the Worker's
 * <b>only</b> business logic for the decoder constraint is a handful of defensive bounds — it never
 * builds, edits, inspects semantically, or re-derives the constraint. Everything here is a rejection
 * (abandon the job) or a verbatim pass-through.
 *
 * <ul>
 *   <li><b>SRO-13:</b> at most one of {@code responseFormatRaw}/{@code jsonSchemaRaw} may be non-null —
 *       both set is {@code CONSTRAINT_INVALID}, checked <em>before</em> any parsing.</li>
 *   <li><b>SOR-06:</b> the size bound is measured on the <b>UTF-8 byte length of the raw text, before
 *       {@code readTree}</b> — never {@code String.length()}, never a post-parse measurement.</li>
 *   <li><b>SOR-07:</b> a parse failure logs the exception <em>class</em> only (WOR-05 precedent) — never
 *       {@link JsonProcessingException#getMessage()}, which quotes the offending source text and could
 *       leak Gateway-supplied (ultimately MR-author-controlled) file-path content into the Worker's own
 *       logs. The parsed node must be a JSON object (a shape check, not a schema check) — {@code
 *       CONSTRAINT_INVALID} otherwise.</li>
 * </ul>
 *
 * <p>The constraint <b>never</b> passes through {@link com.review.worker.prompt.PromptTemplateService} —
 * this class is the only place it is touched between the claim response and the typed {@code
 * ChatCompletionRequest} field it is assigned to (SOR-07: never {@code substitute()}, never a
 * {@code Map}-shaped request-body merge).
 */
@Component
public class DecoderConstraintResolver {

    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;

    public DecoderConstraintResolver(ObjectMapper objectMapper, WorkerProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * @param responseFormatRaw {@code JobPayload.responseFormat()} — Gateway-supplied, untrusted text
     * @param jsonSchemaRaw     {@code JobPayload.jsonSchema()} — Gateway-supplied, untrusted text
     * @throws AbandonJobException with reason {@link JobFailureReason#CONSTRAINT_INVALID} if both fields
     *                              are set, the raw text exceeds {@code worker.limits.max-constraint-bytes},
     *                              it is not valid JSON, or it does not parse to a JSON object
     */
    public DecoderConstraint resolve(String responseFormatRaw, String jsonSchemaRaw) {
        if (responseFormatRaw != null && jsonSchemaRaw != null) {
            throw new AbandonJobException(
                    "Gateway claim payload set both responseFormat and jsonSchema", JobFailureReason.CONSTRAINT_INVALID);
        }
        String raw = responseFormatRaw != null ? responseFormatRaw : jsonSchemaRaw;
        if (raw == null) {
            return DecoderConstraint.NONE;
        }

        long maxBytes = properties.getWorker().getLimits().getMaxConstraintBytes();
        int rawBytes = raw.getBytes(StandardCharsets.UTF_8).length;
        if (rawBytes > maxBytes) {
            // SOR-06: measured on raw UTF-8 bytes, strictly before any parsing is attempted.
            throw new AbandonJobException(
                    "Gateway-supplied decoder constraint (" + rawBytes + " bytes) exceeds "
                            + "worker.limits.max-constraint-bytes (" + maxBytes + ")", JobFailureReason.CONSTRAINT_INVALID);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (JsonProcessingException malformed) {
            // SOR-07/WOR-05: never malformed.getMessage() -- it quotes a source excerpt of the untrusted
            // (ultimately MR-author-controlled) text. Only the exception class is safe to log.
            throw new AbandonJobException(
                    "Gateway-supplied decoder constraint was not valid JSON (" + malformed.getClass().getSimpleName() + ")",
                    JobFailureReason.CONSTRAINT_INVALID);
        }
        if (node == null || !node.isObject()) {
            throw new AbandonJobException(
                    "Gateway-supplied decoder constraint did not parse to a JSON object", JobFailureReason.CONSTRAINT_INVALID);
        }

        return responseFormatRaw != null ? new DecoderConstraint(node, null) : new DecoderConstraint(null, node);
    }
}
