package com.overmind.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.domain.DomainValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ObservedAtPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private final ObservedAtPolicy policy = new ObservedAtPolicy(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void accepts_past_and_exact_future_boundary_and_returns_same_value() {
        Instant past = NOW.minus(Duration.ofDays(3650));
        Instant edge = NOW.plus(Duration.ofMinutes(5));
        assertThat(policy.validate(past)).isSameAs(past);
        assertThat(policy.validate(edge)).isSameAs(edge);
    }

    @Test
    void rejects_one_nanosecond_past_future_boundary() {
        Instant justOver = NOW.plus(Duration.ofMinutes(5)).plusNanos(1);
        assertThatThrownBy(() -> policy.validate(justOver))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejects_null_without_leaking_raw_timestamp() {
        assertThatThrownBy(() -> policy.validate(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("observed_at");
    }

    @Test
    void rejection_message_does_not_include_raw_timestamp() {
        Instant justOver = NOW.plus(Duration.ofHours(1));
        assertThatThrownBy(() -> policy.validate(justOver))
                .hasMessageContaining("observed_at")
                .hasMessageNotContaining("2026-09-02T13:00:00Z");
    }
}
