package com.review.worker.gateway.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FW-05 (SAST report, WSR-10 hardening): {@code toString()} on any record carrying {@code diff} or
 * {@code rawResponse} must never render the raw content, only its length -- a defense against a future
 * accidental {@code log.debug("{}", dto)} dumping proprietary source/model output into logs. Does not
 * affect JSON (de)serialization, which Jackson performs via accessors, not {@code toString()} (verified
 * separately below).
 *
 * <p>Prompt Manager (V3, PMR-25): {@code systemMessages} must be masked identically to {@code diff}/
 * {@code chunkContext} -- counts + total chars only, never section content.
 */
class SensitiveDtoToStringMaskingTest {

    private static final String SECRET_DIFF = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
    private static final String SECRET_RAW_RESPONSE = "THE-SECRET-RAW-MODEL-RESPONSE-CONTENT";
    private static final String SECRET_SYSTEM_MESSAGE = "SECRET-CORPORATE-RULEBOOK-CONTENT";

    @Test
    void jobPayloadToStringNeverContainsTheRawDiff() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v1", null, null, null, null);

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_DIFF.length()));
        assertThat(rendered).contains("v1");
    }

    @Test
    void jobPayloadAccessorStillReturnsTheFullDiffUnmasked() {
        // The masking is toString()-only; the actual field/accessor (what Jackson serializes) must be untouched.
        JobPayload payload = new JobPayload(SECRET_DIFF, "v1", null, null, null, null);

        assertThat(payload.diff()).isEqualTo(SECRET_DIFF);
    }

    @Test
    void resultRequestToStringNeverContainsTheRawResponse() {
        ResultRequest request = new ResultRequest("worker-1", SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x", null);

        String rendered = request.toString();

        assertThat(rendered).doesNotContain(SECRET_RAW_RESPONSE);
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_RAW_RESPONSE.length()));
        // Non-sensitive fields must still be visible for debugging.
        assertThat(rendered).contains("worker-1");
        assertThat(rendered).contains("model-x");
    }

    @Test
    void resultRequestAccessorStillReturnsTheFullRawResponseUnmasked() {
        ResultRequest request = new ResultRequest("worker-1", SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x", null);

        assertThat(request.rawResponse()).isEqualTo(SECRET_RAW_RESPONSE);
    }

    @Test
    void claimResponseToStringNeverContainsTheRawDiffEvenViaNestedPayload() {
        ClaimResponse response = new ClaimResponse(1L, 2L, new JobPayload(SECRET_DIFF, "v1", null, null, null, null));

        String rendered = response.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("jobId=1");
        assertThat(rendered).contains("reviewId=2");
    }

    @Test
    void toStringMaskingHandlesNullContentGracefully() {
        assertThat(new JobPayload(null, "v1", null, null, null, null).toString()).contains("0 chars");
        assertThat(new ResultRequest("w", null, null, null, null, null, null).toString()).contains("0 chars");
    }

    /** V2 (diff chunking): {@code chunkContext} is just as sensitive (MR-author-controlled file names/prompt text) as {@code diff}. */
    @Test
    void jobPayloadToStringNeverContainsTheRawChunkContext() {
        String secretChunkContext = "part 2 of 6\nSECRET-FILE-PATH-CONTENT.java";
        JobPayload payload = new JobPayload("small diff", "v2", secretChunkContext, null, null, null);

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain(secretChunkContext);
        assertThat(rendered).doesNotContain("SECRET-FILE-PATH-CONTENT");
        assertThat(rendered).contains("chunkContext=<masked, " + secretChunkContext.length() + " chars>");
    }

    // ---- Prompt Manager (V3, PMR-25): systemMessages masking ----

    @Test
    void jobPayloadToStringNeverContainsRawSystemMessages() {
        JobPayload payload = new JobPayload("small diff", "v2", null, List.of(SECRET_SYSTEM_MESSAGE, "second message"), null, null);

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE);
        assertThat(rendered).contains("systemMessages=<masked, 2 msg, "
                + (SECRET_SYSTEM_MESSAGE.length() + "second message".length()) + " chars>");
    }

    @Test
    void jobPayloadToStringDistinguishesNullFromEmptySystemMessages() {
        // PMR-24: null (legacy/kill-switch-off) vs [] (Gateway resolved zero sections) must render
        // distinguishably, not collapse to the same "0 chars" text.
        JobPayload nullMessages = new JobPayload("diff", "v1", null, null, null, null);
        JobPayload emptyMessages = new JobPayload("diff", "v1", null, List.of(), null, null);

        assertThat(nullMessages.toString()).contains("systemMessages=null");
        assertThat(emptyMessages.toString()).contains("systemMessages=<masked, 0 msg, 0 chars>");
    }

    @Test
    void jobPayloadAccessorStillReturnsSystemMessagesUnmasked() {
        List<String> messages = List.of(SECRET_SYSTEM_MESSAGE);
        JobPayload payload = new JobPayload("diff", "v2", null, messages, null, null);

        assertThat(payload.systemMessages()).isEqualTo(messages);
    }

    // ---- Structured Review Output (SRO-12): responseFormat/jsonSchema masking ----

    @Test
    void jobPayloadToStringNeverContainsTheRawResponseFormatOrJsonSchema() {
        String secretSchema = "{\"required\":[\"src/SECRET-PATH.java\"]}";
        JobPayload payload = new JobPayload("diff", "v3", null, null, secretSchema, null);

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain("SECRET-PATH");
        assertThat(rendered).contains("responseFormat=<masked, " + secretSchema.length() + " chars>");
        assertThat(rendered).contains("jsonSchema=null");
    }

    @Test
    void jobPayloadAccessorsStillReturnResponseFormatAndJsonSchemaUnmasked() {
        String secretSchema = "{\"required\":[\"src/SECRET-PATH.java\"]}";
        JobPayload payload = new JobPayload("diff", "v3", null, null, null, secretSchema);

        assertThat(payload.jsonSchema()).isEqualTo(secretSchema);
        assertThat(payload.responseFormat()).isNull();
    }

    @Test
    void claimResponseToStringNeverContainsRawSystemMessagesEvenViaNestedPayload() {
        ClaimResponse response = new ClaimResponse(1L, 2L,
                new JobPayload("diff", "v2", null, List.of(SECRET_SYSTEM_MESSAGE), null, null));

        String rendered = response.toString();

        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE);
        assertThat(rendered).contains("masked, 1 msg");
    }
}
