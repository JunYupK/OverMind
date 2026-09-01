package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 진입 문서에 규약 본문이 흘러들어오는 것을 막는다.
 *
 * <p>"두 문서가 일치하는가"는 기계적으로 검사할 수 없다. 대신 길이를 막으면
 * 복제가 시작되는 순간 걸린다.
 */
@Tag("guardrail")
class DocLineLimitGuardTest {

    @Test
    void agents_md_stays_a_routing_table() throws IOException {
        assertLineLimit(Path.of("AGENTS.md"), 120);
    }

    @Test
    void claude_md_stays_a_pointer() throws IOException {
        assertLineLimit(Path.of("CLAUDE.md"), 40);
    }

    private void assertLineLimit(Path file, int limit) throws IOException {
        assertThat(file).exists();
        long lines = Files.readAllLines(file).size();
        assertThat(lines)
                .as(
                        "%s는 %d줄을 넘길 수 없습니다. 규약 본문은 docs/harness/로 옮기세요",
                        file, limit)
                .isLessThanOrEqualTo(limit);
    }
}
