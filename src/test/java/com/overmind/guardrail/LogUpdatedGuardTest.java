package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * src/ 를 고쳤으면 log.md도 같이 고쳐야 한다.
 *
 * <p>이 게이트를 CI에 둔 이유는 도구 중립성이다. Claude Code hooks는 Claude Code에서만
 * 돌지만, CI는 Codex든 웹 LLM이든 사람이든 똑같이 걸린다.
 *
 * <p>base ref를 찾을 수 없는 로컬 환경에서는 검사를 건너뛴다. 진짜 게이트는 CI다.
 */
@Tag("guardrail")
class LogUpdatedGuardTest {

    @Test
    void source_changes_come_with_a_log_update() {
        String baseRef = System.getProperty("overmind.guardrail.baseRef", "origin/master");

        assumeTrue(
                git("rev-parse", "--verify", baseRef).exitCode() == 0,
                "base ref '" + baseRef + "' 를 찾을 수 없어 검사를 건너뜁니다 (CI에서는 항상 존재)");

        GitResult diff = git("diff", "--name-only", baseRef + "...HEAD");
        assumeTrue(diff.exitCode() == 0, "git diff 실패 — 검사를 건너뜁니다");

        List<String> changed = diff.lines();
        boolean touchedSource = changed.stream().anyMatch(path -> path.startsWith("src/"));
        boolean touchedLog = changed.contains("log.md");

        if (!touchedSource) {
            return;
        }

        assertThat(touchedLog)
                .as(
                        "src/ 를 변경했으면 log.md도 갱신해야 합니다. "
                                + "HEAD 블록을 덮어쓰고 세션 기록을 추가하세요. 변경된 파일: %s",
                        changed)
                .isTrue();
    }

    private static GitResult git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
            return new GitResult(process.waitFor(), lines);
        } catch (IOException | InterruptedException e) {
            return new GitResult(-1, List.of());
        }
    }

    private record GitResult(int exitCode, List<String> lines) {}
}
