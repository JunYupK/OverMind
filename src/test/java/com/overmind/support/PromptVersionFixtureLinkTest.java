package com.overmind.support;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.application.port.PromptVersions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. 프롬프트 버전과 픽스처 디렉터리가 일치하는지 검사한다.
 *
 * <p>프롬프트를 고치고 픽스처 재녹화를 잊는 것이 이 구조의 가장 흔한 실패다.
 * 이름을 묶어두면 기계가 잡는다.
 */
class PromptVersionFixtureLinkTest {

    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/llm-fixtures");

    @Test
    void declared_prompt_versions_and_fixture_directories_match() throws IOException {
        assertThat(FIXTURE_ROOT)
                .as("픽스처 루트 디렉터리가 있어야 합니다")
                .isDirectory();

        Set<String> directories;
        try (Stream<Path> entries = Files.list(FIXTURE_ROOT)) {
            directories =
                    entries.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .collect(toSet());
        }

        assertThat(directories)
                .as(
                        "픽스처 디렉터리와 PromptVersions.all()이 일치해야 합니다. "
                                + "프롬프트 버전을 올렸으면 -Dovermind.llm.record=true 로 재녹화하세요")
                .isEqualTo(PromptVersions.all());
    }
}
