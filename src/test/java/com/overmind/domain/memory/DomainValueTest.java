package com.overmind.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** L1. 스펙 §3, §4.2의 도메인 생성 규칙. */
class DomainValueTest {

    private static String utf8Bytes(int byteCount) {
        // '가'는 UTF-8에서 3 bytes다. byteCount가 3의 배수가 아니면 'a'로 채운다.
        StringBuilder sb = new StringBuilder();
        int remaining = byteCount;
        while (remaining >= 3) {
            sb.append('가');
            remaining -= 3;
        }
        sb.append("a".repeat(remaining));
        return sb.toString();
    }

    @Test
    void content_rejects_blank() {
        assertThatThrownBy(() -> ObservationContent.of("   \n\t "))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void content_rejects_null() {
        assertThatThrownBy(() -> ObservationContent.of(null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void content_accepts_exactly_16_kib_of_utf8() {
        String value = utf8Bytes(16 * 1024);
        assertThat(value.getBytes(StandardCharsets.UTF_8)).hasSize(16 * 1024);

        ObservationContent content = ObservationContent.of(value);

        assertThat(content.utf8Size()).isEqualTo(16 * 1024);
    }

    @Test
    void content_rejects_one_byte_over_16_kib() {
        String value = utf8Bytes(16 * 1024) + "a";

        assertThatThrownBy(() -> ObservationContent.of(value))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void content_limit_counts_bytes_not_characters() {
        // 문자 수로 세면 통과하고 byte 수로 세면 거부되는 지점.
        String value = "가".repeat(16 * 1024 / 3 + 1);
        assertThat(value.length()).isLessThan(16 * 1024);
        assertThat(value.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(16 * 1024);

        assertThatThrownBy(() -> ObservationContent.of(value))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void content_preserves_exact_nonblank_raw_text() {
        String value = "  exact text  \n";

        assertThat(ObservationContent.of(value).value()).isEqualTo(value);
    }

    @Test
    void project_key_accepts_lowercase_ascii_forms() {
        assertThat(ProjectKey.of("overmind").value()).isEqualTo("overmind");
        assertThat(ProjectKey.of("0").value()).isEqualTo("0");
        assertThat(ProjectKey.of("a.b_c-d").value()).isEqualTo("a.b_c-d");
    }

    @Test
    void project_key_rejects_invalid_forms() {
        assertThatThrownBy(() -> ProjectKey.of(null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectKey.of("Overmind"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectKey.of("-leading-dash"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectKey.of(".leading-dot"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectKey.of("has space"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectKey.of(""))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProjectKey.of("프로젝트"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void project_key_accepts_128_chars_and_rejects_129() {
        assertThat(ProjectKey.of("a".repeat(128)).value()).hasSize(128);

        assertThatThrownBy(() -> ProjectKey.of("a".repeat(129)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void idempotency_key_accepts_256_bytes_and_rejects_257() {
        assertThat(IdempotencyKey.of(utf8Bytes(256)).value()).isNotBlank();

        assertThatThrownBy(() -> IdempotencyKey.of(utf8Bytes(256) + "a"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void idempotency_key_rejects_blank() {
        assertThatThrownBy(() -> IdempotencyKey.of(null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> IdempotencyKey.of(" "))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void idempotency_key_preserves_exact_nonblank_raw_text() {
        String value = " key-with-padding ";

        assertThat(IdempotencyKey.of(value).value()).isEqualTo(value);
    }

    @Test
    void source_reference_requires_every_field() {
        assertThatThrownBy(() -> SourceReference.of(null, "c", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of("example-client", " ", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of("example-client", "c", ""))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void source_reference_rejects_null_or_blank_in_each_field() {
        assertThatThrownBy(() -> SourceReference.of(null, null, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of(" ", "c", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of("client", null, "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of("client", " ", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of("client", "conversation", null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> SourceReference.of("client", "conversation", "\t\n"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void source_reference_enforces_byte_limits() {
        assertThat(SourceReference.of(utf8Bytes(128), utf8Bytes(512), utf8Bytes(512)).client())
                .isNotBlank();

        assertThatThrownBy(
                        () -> SourceReference.of(utf8Bytes(128) + "a", "c", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () -> SourceReference.of("example-client", utf8Bytes(512) + "a", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(
                        () -> SourceReference.of("example-client", "c", utf8Bytes(512) + "a"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void source_reference_preserves_exact_nonblank_raw_text() {
        SourceReference source = SourceReference.of(" client ", " conversation ", " message ");

        assertThat(source.client()).isEqualTo(" client ");
        assertThat(source.conversationId()).isEqualTo(" conversation ");
        assertThat(source.messageId()).isEqualTo(" message ");
    }

    @Test
    void values_are_equal_by_content() {
        assertThat(ProjectKey.of("x")).isEqualTo(ProjectKey.of("x"));
        assertThat(ProjectKey.of("x")).hasSameHashCodeAs(ProjectKey.of("x"));
        assertThat(IdempotencyKey.of("k")).isEqualTo(IdempotencyKey.of("k"));
        assertThat(IdempotencyKey.of("k")).hasSameHashCodeAs(IdempotencyKey.of("k"));
        assertThat(ObservationContent.of("c")).isEqualTo(ObservationContent.of("c"));
        assertThat(ObservationContent.of("c")).hasSameHashCodeAs(ObservationContent.of("c"));
        assertThat(SourceReference.of("a", "b", "c"))
                .isEqualTo(SourceReference.of("a", "b", "c"));
        assertThat(SourceReference.of("a", "b", "c"))
                .hasSameHashCodeAs(SourceReference.of("a", "b", "c"));
    }

    @Test
    void source_reference_equality_differs_by_each_source_field() {
        SourceReference source = SourceReference.of("client", "conversation", "message");

        assertThat(source).isNotEqualTo(SourceReference.of("other-client", "conversation", "message"));
        assertThat(source).isNotEqualTo(SourceReference.of("client", "other-conversation", "message"));
        assertThat(source).isNotEqualTo(SourceReference.of("client", "conversation", "other-message"));
    }

    @Test
    void validation_messages_do_not_include_raw_sensitive_input() {
        String sensitive = "sensitive-memory-payload-123";

        assertThatThrownBy(() -> ObservationContent.of(sensitive.repeat(700)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageNotContaining(sensitive);
        assertThatThrownBy(() -> ProjectKey.of("bad key with sensitive-memory-payload-123"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageNotContaining(sensitive);
        assertThatThrownBy(() -> IdempotencyKey.of(sensitive.repeat(20)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageNotContaining(sensitive);
        assertThatThrownBy(
                        () -> SourceReference.of(sensitive.repeat(10), "conversation", "message"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageNotContaining(sensitive);
    }
}
