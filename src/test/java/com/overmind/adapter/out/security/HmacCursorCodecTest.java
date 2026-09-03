package com.overmind.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.memory.RecallCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * L1. Spec §5.3: the cursor is stateless, signed, and pinned to one subject filter.
 *
 * <p>Secrets here are built by repeating words rather than written as one high-entropy
 * literal. gitleaks scans this repository and flags {@code key=<random string>} shapes,
 * and a fabricated literal is indistinguishable from a real leak to that scanner.
 */
class HmacCursorCodecTest {

    private static final byte[] SECRET =
            "overmind-test-cursor-key-".repeat(2).getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET =
            "overmind-test-other-key-".repeat(2).getBytes(StandardCharsets.UTF_8);

    private static final UUID SUBJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID SUBJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID OBSERVATION =
            UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private final HmacCursorCodec codec = new HmacCursorCodec(SECRET);
    private final HmacCursorCodec otherCodec = new HmacCursorCodec(OTHER_SECRET);

    private static RecallCursor cursor() {
        return new RecallCursor(
                Instant.parse("2026-09-02T10:00:00.000001Z"),
                Instant.parse("2026-09-02T10:00:01.000002Z"),
                OBSERVATION,
                RecallCursor.filterFingerprint(List.of(SUBJECT_A)));
    }

    @Test
    void a_cursor_round_trips() {
        String token = codec.encode(cursor());

        assertThat(codec.decode(token, cursor().filterFingerprint())).isEqualTo(cursor());
    }

    @Test
    void a_token_signed_with_another_secret_is_rejected() {
        String token = otherCodec.encode(cursor());

        assertInvalidCursor(() -> codec.decode(token, cursor().filterFingerprint()));
    }

    @Test
    void a_tampered_signature_is_rejected() {
        String token = codec.encode(cursor());
        String tampered = token.substring(0, token.length() - 2) + "AA";

        assertInvalidCursor(() -> codec.decode(tampered, cursor().filterFingerprint()));
    }

    @Test
    void a_tampered_payload_is_rejected() {
        String token = codec.encode(cursor());
        int firstDot = token.indexOf('.');
        int lastDot = token.lastIndexOf('.');
        String forgedPayload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("0:0|0:0|"
                                                + OBSERVATION
                                                + "|"
                                                + cursor().filterFingerprint())
                                        .getBytes(StandardCharsets.UTF_8));
        String forged =
                token.substring(0, firstDot + 1) + forgedPayload + token.substring(lastDot);

        assertInvalidCursor(() -> codec.decode(forged, cursor().filterFingerprint()));
    }

    @Test
    void a_cursor_from_a_different_subject_filter_is_rejected() {
        String token = codec.encode(cursor());
        String otherFilter = RecallCursor.filterFingerprint(List.of(SUBJECT_A, SUBJECT_B));

        assertInvalidCursor(() -> codec.decode(token, otherFilter));
    }

    @Test
    void an_unknown_version_is_rejected() {
        String token = codec.encode(cursor()).replaceFirst("^v1", "v2");

        assertInvalidCursor(() -> codec.decode(token, cursor().filterFingerprint()));
    }

    @Test
    void garbage_is_rejected_rather_than_silently_resetting_to_the_first_page() {
        for (String bad : List.of("", "   ", "not-a-cursor", "v1.@@@.###", "v1.only-two-parts")) {
            assertInvalidCursor(() -> codec.decode(bad, cursor().filterFingerprint()));
        }
        assertInvalidCursor(() -> codec.decode(null, cursor().filterFingerprint()));
    }

    @Test
    void the_payload_carries_no_raw_subject_identity() {
        String token = codec.encode(cursor());
        String payload =
                new String(
                        Base64.getUrlDecoder()
                                .decode(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'))),
                        StandardCharsets.UTF_8);

        assertThat(payload)
                .as("spec §5.3 forbids raw subject identity inside the cursor payload")
                .doesNotContain(SUBJECT_A.toString());
    }

    @Test
    void the_error_message_does_not_echo_the_rejected_token() {
        String magic = "MAGICTOKEN" + "4f1c8b";

        assertThatThrownBy(() -> codec.decode(magic, cursor().filterFingerprint()))
                .hasMessageNotContaining(magic);
    }

    @Test
    void the_fingerprint_ignores_subject_id_order() {
        assertThat(RecallCursor.filterFingerprint(List.of(SUBJECT_A, SUBJECT_B)))
                .isEqualTo(RecallCursor.filterFingerprint(List.of(SUBJECT_B, SUBJECT_A)));
    }

    @Test
    void different_subject_filters_get_different_fingerprints() {
        assertThat(RecallCursor.filterFingerprint(List.of(SUBJECT_A)))
                .isNotEqualTo(RecallCursor.filterFingerprint(List.of(SUBJECT_A, SUBJECT_B)));
    }

    @Test
    void a_short_secret_is_refused_at_construction() {
        assertThatThrownBy(() -> new HmacCursorCodec("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HmacCursorCodec(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalidCursor(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(MemoryException.class)
                .extracting(thrown -> ((MemoryException) thrown).code())
                .isEqualTo(MemoryErrorCode.INVALID_CURSOR);
    }
}
