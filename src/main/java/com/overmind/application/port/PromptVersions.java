package com.overmind.application.port;

import java.util.Set;

/**
 * 선언된 프롬프트 버전.
 *
 * <p>이 상수는 세 곳과 묶여 있다.
 *
 * <ol>
 *   <li>{@code src/test/resources/llm-fixtures/<버전>/} 디렉터리 이름
 *   <li>이 클래스의 상수
 *   <li>(M0 이후) {@code observation.extractor_version} 컬럼 값
 * </ol>
 *
 * <p>어긋나면 {@code PromptVersionFixtureLinkTest}가 실패한다.
 */
public final class PromptVersions {

    /** 대화에서 observation을 추출하는 프롬프트. */
    public static final String EXTRACTOR = "extractor-v1";

    private PromptVersions() {}

    public static Set<String> all() {
        return Set.of(EXTRACTOR);
    }
}
