package com.overmind.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. AR-4 / INV-01 — 프로바이더 고유명이 코어 도메인에 누출되지 않는다.
 *
 * <p>타입명뿐 아니라 식별자와 문자열 리터럴까지 잡아야 하므로 소스를 직접 읽는다.
 * ArchUnit은 바이트코드를 보기 때문에 문자열 리터럴 내용을 검사할 수 없다.
 *
 * <p>검사는 <b>대소문자를 구분하지 않는다.</b> 이 이름들이 코드에 실제로 나타나는 형태는
 * 소문자다 — 설정 키, 프로퍼티 이름, enum 값, 모델 id. {@code "Anthropic"}만 막고
 * {@code DEFAULT_VENDOR = "anthropic"} 을 통과시키면 가드가 아니다.
 *
 * <p>정상 단어와 충돌하는 항목이 생기면 <b>용어를 빼지 말고 패턴을 좁힌다.</b>
 * {@code gpt}가 그 예다 — 알파벳에 둘러싸인 경우는 제외해서
 * {@code encrypted}·{@code gptCache} 류의 오탐을 피하되 {@code gpt-4}·{@code gpt_4o}는 잡는다.
 */
class ProviderNameLeakTest {

    /** 소문자로 접은 줄에 대해 검사한다. 값은 정규식이다. */
    private static final List<Pattern> FORBIDDEN =
            Stream.of(
                            "claude",
                            "chatgpt",
                            "openai",
                            "anthropic",
                            "gemini",
                            "llama",
                            "mistral",
                            "bedrock",
                            "vertex",
                            // 짧아서 다른 단어에 박히기 쉽다. 앞뒤가 알파벳이면 무시한다.
                            "(?<![a-z])gpt(?![a-z])")
                    .map(Pattern::compile)
                    .toList();

    private static final List<Path> SCANNED_ROOTS =
            List.of(
                    Path.of("src/main/java/com/overmind/domain"),
                    Path.of("src/main/java/com/overmind/application"));

    @Test
    void core_domain_contains_no_provider_names() throws IOException {
        List<String> hits = new ArrayList<>();

        for (Path root : SCANNED_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> javaFiles =
                        paths.filter(p -> p.toString().endsWith(".java")).toList();
                for (Path file : javaFiles) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String folded = lines.get(i).toLowerCase(java.util.Locale.ROOT);
                        for (Pattern pattern : FORBIDDEN) {
                            if (pattern.matcher(folded).find()) {
                                hits.add(file + ":" + (i + 1) + " → " + pattern.pattern());
                            }
                        }
                    }
                }
            }
        }

        assertThat(hits)
                .as(
                        "AR-4 / INV-01: 프로바이더 고유명이 코어 도메인에 누출되었습니다. "
                                + "프로바이더 차이는 adapter 안에 가둡니다 (검사는 대소문자를 구분하지 않습니다)")
                .isEmpty();
    }
}
