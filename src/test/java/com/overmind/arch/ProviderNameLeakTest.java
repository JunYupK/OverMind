package com.overmind.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. AR-4 / INV-01 — 프로바이더 고유명이 코어 도메인에 누출되지 않는다.
 *
 * <p>타입명뿐 아니라 식별자와 문자열 리터럴까지 잡아야 하므로 소스를 직접 읽는다.
 * ArchUnit은 바이트코드를 보기 때문에 문자열 리터럴 내용을 검사할 수 없다.
 */
class ProviderNameLeakTest {

    private static final List<String> FORBIDDEN =
            List.of("Claude", "ChatGPT", "OpenAI", "Anthropic", "Gemini");

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
                        for (String word : FORBIDDEN) {
                            if (lines.get(i).contains(word)) {
                                hits.add(file + ":" + (i + 1) + " → " + word);
                            }
                        }
                    }
                }
            }
        }

        assertThat(hits)
                .as(
                        "AR-4 / INV-01: 프로바이더 고유명이 코어 도메인에 누출되었습니다. "
                                + "프로바이더 차이는 adapter 안에 가둡니다")
                .isEmpty();
    }
}
