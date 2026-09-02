package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Validates caller supplied observation timestamps against server time. */
public final class ObservedAtPolicy {
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    public ObservedAtPolicy(Clock clock) {
        this.clock = clock;
    }

    public Instant validate(Instant observedAt) {
        if (observedAt == null) {
            throw new DomainValidationException("observed_at 이 없습니다");
        }
        if (observedAt.isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            throw new DomainValidationException("observed_at 이 허용 범위를 벗어났습니다");
        }
        return observedAt;
    }
}
