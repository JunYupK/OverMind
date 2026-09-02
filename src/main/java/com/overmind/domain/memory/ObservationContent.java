package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 관측된 원문. 서버는 이것을 문장이나 사실 단위로 쪼개지 않는다.
 *
 * <p>상한은 문자 수가 아니라 UTF-8 byte 수다. 한글은 문자당 3 bytes이므로
 * 문자 수로 세면 상한이 세 배로 늘어난다.
 */
public final class ObservationContent {

    public static final int MAX_UTF8_BYTES = 16 * 1024;

    private final String value;
    private final int utf8Size;

    private ObservationContent(String value, int utf8Size) {
        this.value = value;
        this.utf8Size = utf8Size;
    }

    public static ObservationContent of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainValidationException("content가 비어 있습니다");
        }
        int size = raw.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_UTF8_BYTES) {
            throw new DomainValidationException(
                    "content가 " + MAX_UTF8_BYTES + " bytes를 넘습니다 (" + size + " bytes)");
        }
        return new ObservationContent(raw, size);
    }

    public String value() {
        return value;
    }

    public int utf8Size() {
        return utf8Size;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ObservationContent that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
