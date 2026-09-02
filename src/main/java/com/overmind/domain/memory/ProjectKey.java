package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PROJECT를 가리키는 안정 키. 표시 이름이 아니라 식별자다.
 *
 * <p>1~128자, 소문자 ASCII. 첫 글자는 영숫자여야 한다.
 */
public final class ProjectKey {

    private static final Pattern FORM = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final int MAX_LENGTH = 128;

    private final String value;

    private ProjectKey(String value) {
        this.value = value;
    }

    public static ProjectKey of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new DomainValidationException("project key가 비어 있습니다");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "project key가 " + MAX_LENGTH + "자를 넘습니다 (길이 " + raw.length() + ")");
        }
        if (!FORM.matcher(raw).matches()) {
            throw new DomainValidationException(
                    "project key 형식이 올바르지 않습니다. 소문자 ASCII로 [a-z0-9][a-z0-9._-]* 를 만족해야 합니다");
        }
        return new ProjectKey(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectKey that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
