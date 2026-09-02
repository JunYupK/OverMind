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
 * 감시 경로를 고쳤으면 log.md도 같이 고쳐야 한다.
 *
 * <p>이 게이트를 CI에 둔 이유는 도구 중립성이다. Claude Code hooks는 Claude Code에서만
 * 돌지만, CI는 Codex든 웹 LLM이든 사람이든 똑같이 걸린다.
 *
 * <p><b>범위는 커밋이 아니라 {@code baseRef...HEAD} 범위다.</b> PR/브랜치 안 어딘가에서
 * log.md가 갱신되면 통과한다. 커밋 단위 강제는 rebase·squash·fixup과 싸우게 되고,
 * 그 싸움에서 이기지 못하면 가드가 꺼진다. AGENTS.md 절대 규칙 3이 이 범위로 진술되어 있다.
 *
 * <p>감시 경로에 {@code src/} 뿐 아니라 {@code build.gradle.kts}, {@code .github/},
 * {@code docs/harness/}가 들어간다. 이것들이 게이트 기계 자체이기 때문이다.
 * 게이트 결함을 고친 커밋이 아무 기록도 남기지 않고 지나가면, 다음 세션은 그 게이트가
 * 왜 지금 모양인지 알 방법이 없다.
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
        List<String> watchedHits = changed.stream().filter(LogUpdatedGuardTest::isWatched).toList();
        boolean touchedLog = changed.contains("log.md");

        if (watchedHits.isEmpty()) {
            return;
        }

        assertThat(touchedLog)
                .as(
                        "%s 범위에서 감시 경로를 변경했으면 같은 범위 안에서 log.md도 갱신해야 합니다. "
                                + "HEAD 블록을 덮어쓰고 세션 기록을 추가하세요. 걸린 경로: %s",
                        baseRef, watchedHits)
                .isTrue();
    }

    /**
     * log.md 동반 갱신을 강제할 경로들.
     *
     * <p>제품 코드({@code src/})와 <b>게이트 기계 자체</b>를 모두 본다.
     * 게이트를 고치는 변경이야말로 "왜 그렇게 했는지"가 git diff에 안 남는 변경이다.
     */
    private static final List<String> WATCHED_PREFIXES =
            List.of("src/", ".github/", "docs/harness/");

    private static final List<String> WATCHED_FILES = List.of("build.gradle.kts");

    private static boolean isWatched(String path) {
        return WATCHED_PREFIXES.stream().anyMatch(path::startsWith) || WATCHED_FILES.contains(path);
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
