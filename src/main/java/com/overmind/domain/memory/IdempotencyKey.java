package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 호출자가 정하는 멱등 키. 재시도에는 같은 키를 쓴다.
 *
 * <p>서버가 만들지 않는다 — 서버 생성 UUID는 재시도를 새 요청과 구별하지 못한다.
 */
public final class IdempotencyKey {

    public static final int MAX_UTF8_BYTES = 256;

    private final String value;

    private IdempotencyKey(String value) {
        this.value = value;
    }

    public static IdempotencyKey of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainValidationException("idempotency key가 비어 있습니다");
        }
        int size = raw.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_UTF8_BYTES) {
            throw new DomainValidationException(
                    "idempotency key가 " + MAX_UTF8_BYTES + " bytes를 넘습니다 (" + size + " bytes)");
        }
        return new IdempotencyKey(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdempotencyKey that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
