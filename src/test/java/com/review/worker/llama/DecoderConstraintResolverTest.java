package com.review.worker.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.error.JobFailureReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DecoderConstraintResolver} (architecture §3.3, threat model SOR-06/SOR-07/SRO-13).
 */
class DecoderConstraintResolverTest {

    private WorkerProperties newProperties(long maxConstraintBytes) {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getWorker().getLimits().setMaxConstraintBytes(maxConstraintBytes);
        return properties;
    }

    private DecoderConstraintResolver newResolver(long maxConstraintBytes) {
        return new DecoderConstraintResolver(new ObjectMapper(), newProperties(maxConstraintBytes));
    }

    @Test
    void bothNullProducesNone() {
        DecoderConstraint result = newResolver(1000).resolve(null, null);

        assertThat(result).isEqualTo(DecoderConstraint.NONE);
    }

    @Test
    void bothNonNullIsRejectedBeforeAnyParsing() {
        DecoderConstraintResolver resolver = newResolver(1000);

        assertThatThrownBy(() -> resolver.resolve("{}", "{}"))
                .isInstanceOf(AbandonJobException.class)
                .satisfies(e -> assertThat(((AbandonJobException) e).getReason())
                        .isEqualTo(JobFailureReason.CONSTRAINT_INVALID));
    }

    @Test
    void responseFormatAloneParsesIntoTheResponseFormatField() {
        DecoderConstraint result = newResolver(1000).resolve("{\"type\":\"json_schema\"}", null);

        assertThat(result.responseFormat()).isNotNull();
        assertThat(result.responseFormat().get("type").asText()).isEqualTo("json_schema");
        assertThat(result.jsonSchema()).isNull();
    }

    @Test
    void jsonSchemaAloneParsesIntoTheJsonSchemaField() {
        DecoderConstraint result = newResolver(1000).resolve(null, "{\"type\":\"object\"}");

        assertThat(result.jsonSchema()).isNotNull();
        assertThat(result.jsonSchema().get("type").asText()).isEqualTo("object");
        assertThat(result.responseFormat()).isNull();
    }

    @Test
    void exceedingMaxConstraintBytesIsRejectedBeforeParsing() {
        // A schema-shaped string, but the byte bound fires first regardless of its content.
        String oversized = "{\"padding\":\"" + "x".repeat(2000) + "\"}";
        DecoderConstraintResolver resolver = newResolver(100);

        assertThatThrownBy(() -> resolver.resolve(oversized, null))
                .isInstanceOf(AbandonJobException.class)
                .satisfies(e -> assertThat(((AbandonJobException) e).getReason())
                        .isEqualTo(JobFailureReason.CONSTRAINT_INVALID));
    }

    @Test
    void aSchemaExactlyAtMaxConstraintBytesPasses() {
        // Byte length must be measured on UTF-8 bytes, not String.length() -- use only ASCII here so the
        // two coincide, and assert the boundary is inclusive (SOR-06: "AT exactly max-schema-bytes passes").
        String base = "{\"a\":\"";
        String suffix = "\"}";
        int fillLength = 50 - base.length() - suffix.length();
        String exact = base + "x".repeat(fillLength) + suffix;
        assertThat(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(50);

        DecoderConstraint result = newResolver(50).resolve(exact, null);

        assertThat(result.responseFormat()).isNotNull();
    }

    @Test
    void byteLengthIsMeasuredOnUtf8BytesNotCharLength() {
        // Each 'é' is 1 char but 2 UTF-8 bytes -- a resolver that measured String.length() would let this
        // through at a byte cap that should reject it.
        String nonAscii = "{\"path\":\"" + "é".repeat(60) + "\"}";
        int charLength = nonAscii.length();
        int byteLength = nonAscii.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(byteLength).isGreaterThan(charLength);

        DecoderConstraintResolver resolver = newResolver(charLength); // fits by char count, not by byte count

        assertThatThrownBy(() -> resolver.resolve(nonAscii, null))
                .isInstanceOf(AbandonJobException.class);
    }

    @Test
    void notValidJsonIsRejectedAndNeverLogsTheSourceText() {
        DecoderConstraintResolver resolver = newResolver(1000);

        assertThatThrownBy(() -> resolver.resolve("not valid json {{{", null))
                .isInstanceOf(AbandonJobException.class)
                .satisfies(e -> {
                    assertThat(((AbandonJobException) e).getReason()).isEqualTo(JobFailureReason.CONSTRAINT_INVALID);
                    // SOR-07/WOR-05: the message must never quote Jackson's own exception message (which
                    // includes a source excerpt) -- only a static description plus the exception class name.
                    assertThat(e.getMessage()).doesNotContain("not valid json {{{");
                });
    }

    @Test
    void aJsonArrayIsRejectedAsNotAnObject() {
        DecoderConstraintResolver resolver = newResolver(1000);

        assertThatThrownBy(() -> resolver.resolve("[1,2,3]", null))
                .isInstanceOf(AbandonJobException.class)
                .satisfies(e -> assertThat(((AbandonJobException) e).getReason())
                        .isEqualTo(JobFailureReason.CONSTRAINT_INVALID));
    }

    @Test
    void aJsonScalarIsRejectedAsNotAnObject() {
        DecoderConstraintResolver resolver = newResolver(1000);

        assertThatThrownBy(() -> resolver.resolve("\"just a string\"", null))
                .isInstanceOf(AbandonJobException.class);
    }
}
