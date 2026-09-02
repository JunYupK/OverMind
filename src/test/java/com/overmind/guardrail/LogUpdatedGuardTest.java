package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * <p>감시 경로는 제품 코드, 게이트 기계 자체, 그리고 설계·결정 문서를 모두 덮는다.
 * 게이트 결함을 고친 커밋이나 스펙을 쓴 세션이 아무 기록도 남기지 않고 지나가면,
 * 다음 세션은 그것이 왜 지금 모양인지 알 방법이 없다. 정확한 목록은
 * {@link #WATCHED_PREFIXES}와 {@link #WATCHED_FILES}에 있다.
 *
 * <p><b>경로는 {@code -z}로 받는다.</b> {@code core.quotePath}가 켜져 있으면(기본값)
 * {@code git diff --name-only}는 한글이 든 이름을 {@code "docs/arch/\354\204\244..."}처럼
 * 따옴표와 8진 이스케이프로 감싸서 낸다. 그러면 접두사 비교가 전부 빗나가 감시 경로 변경이
 * 통과한다 — 한국어로 쓰는 저장소에서 특히 잘 밟는다. NUL 구분 출력에는 인용이 없다.
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

        GitResult diff = git(null, diffArgs(baseRef));
        assumeTrue(diff.exitCode() == 0, "git diff 실패 — 검사를 건너뜁니다");

        List<String> changed = splitNulPaths(diff.stdout());
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
     * <p>세 부류를 본다.
     *
     * <ol>
     *   <li><b>제품 코드</b> — {@code src/}
     *   <li><b>게이트 기계 자체</b> — {@code build.gradle.kts},
     *       {@code settings.gradle.kts}, {@code .github/}, {@code docs/harness/}.
     *       게이트를 고치는 변경이야말로 "왜 그렇게 했는지"가 git diff에 안 남는
     *       변경이다. 빌드 설정은 두 파일로 갈라져 있으므로 둘 다 본다 — 한쪽만 보면
     *       모듈 추가·툴체인 교체가 기록 없이 지나간다.
     *   <li><b>설계와 결정</b> — {@code docs/superpowers/}, {@code docs/arch/},
     *       {@code docs/requirements/}, {@code AGENTS.md}, {@code CLAUDE.md}.
     *       스펙·플랜을 쓰는 세션은 코드를 한 줄도 안 건드릴 수 있는데, 그러면 설계
     *       세션 하나가 통째로 로그에 흔적을 남기지 않고 지나간다. 결정 레지스터와
     *       진입 문서도 마찬가지다.
     * </ol>
     *
     * <p><b>이 목록이 진실의 원천이다.</b> {@code AGENTS.md} 절대 규칙 3과
     * {@code docs/harness/40-guardrails.md}의 표는 사본이며,
     * {@link WatchedPathSyncGuardTest}가 그 사본을 여기와 집합 비교한다. 한쪽만 고치면
     * 실패한다 — 주석으로 "같아야 한다"고 적어 두는 것은 게이트가 아니다.
     */
    static final List<String> WATCHED_PREFIXES =
            List.of(
                    "src/",
                    ".github/",
                    "docs/harness/",
                    "docs/superpowers/",
                    "docs/arch/",
                    "docs/requirements/");

    static final List<String> WATCHED_FILES =
            List.of("build.gradle.kts", "settings.gradle.kts", "AGENTS.md", "CLAUDE.md");

    /**
     * 변경 경로를 받아오는 명령. 테스트가 같은 인자를 쓰므로 {@code -z}가 빠지면 드러난다.
     */
    private static String[] diffArgs(String baseRef) {
        return new String[] {"diff", "--name-only", "-z", baseRef + "...HEAD"};
    }

    /** NUL로 구분된 경로 목록. 이름에 개행이 들어 있어도 쪼개지지 않는다. */
    private static List<String> splitNulPaths(String raw) {
        return Arrays.stream(raw.split("\u0000", -1)).filter(name -> !name.isEmpty()).toList();
    }

    /** 소스 인코딩에 흔들리지 않도록 이스케이프로 적는다 — "docs/arch/설계.md". */
    private static final String NON_ASCII_PATH = "docs/arch/\uc124\uacc4.md";

    @Test
    void non_ascii_watched_paths_reach_the_filter(@TempDir Path tmp) throws Exception {
        Fixture fixture = seedRepoTouching(tmp, NON_ASCII_PATH);

        GitResult diff = git(fixture.repo(), diffArgs(fixture.base()));

        assertThat(diff.exitCode()).as("프로브 저장소에서 git diff 실패: %s", diff.lines()).isZero();
        List<String> changed = splitNulPaths(diff.stdout());
        assertThat(changed).containsExactly(NON_ASCII_PATH);
        assertThat(changed.stream().anyMatch(LogUpdatedGuardTest::isWatched))
                .as("한글이 든 감시 경로가 필터에 닿아야 합니다. 닿은 목록: %s", changed)
                .isTrue();
    }

    /**
     * 이 가드가 막고 있는 것이 실재함을 못 박는다. {@code -z} 없이 같은 diff를 부르면 git이
     * 이름을 인용해서 내고, 그러면 감시 경로 변경이 아무 표시 없이 통과한다.
     *
     * <p>이 테스트가 깨지면 git이 더 이상 인용하지 않는다는 뜻이다. {@code -z}는 그래도
     * 무해하므로 구현이 아니라 이 테스트를 갱신하면 된다.
     */
    @Test
    void without_z_the_same_change_slips_through(@TempDir Path tmp) throws Exception {
        Fixture fixture = seedRepoTouching(tmp, NON_ASCII_PATH);

        GitResult quoted =
                git(fixture.repo(), "diff", "--name-only", fixture.base() + "...HEAD");

        assertThat(quoted.lines()).allSatisfy(name -> assertThat(name).startsWith("\""));
        assertThat(quoted.lines().stream().anyMatch(LogUpdatedGuardTest::isWatched))
                .as("인용된 이름은 감시 경로에 걸리지 않는다 — 이것이 -z를 쓰는 이유다. 받은 목록: %s", quoted.lines())
                .isFalse();
    }

    /**
     * 프로브 저장소를 만든다. 경로 이름을 셸 스크립트에 UTF-8 바이트로 적어 실행하는 이유는,
     * ProcessBuilder 인자와 파일 이름이 JVM의 {@code sun.jnu.encoding}을 타기 때문이다.
     * 러너 로케일이 ASCII면 Java가 만든 이름은 이미 망가진 채로 들어가 검사가 무의미해진다.
     */
    private static Fixture seedRepoTouching(Path tmp, String path) throws Exception {
        Path repo = tmp.resolve("repo");
        Path baseFile = tmp.resolve("BASE");
        Path script = tmp.resolve("seed.sh");
        String source =
                "set -e\n"
                        + "git init -q \"$1\"\n"
                        + "cd \"$1\"\n"
                        + "git config user.email probe@example.com\n"
                        + "git config user.name probe\n"
                        + "git commit -q --allow-empty -m base\n"
                        + "git rev-parse HEAD > \"$2\"\n"
                        + "mkdir -p \"$(dirname '"
                        + path
                        + "')\"\n"
                        + "printf probe > '"
                        + path
                        + "'\n"
                        + "git add -A\n"
                        + "git commit -q -m change\n";
        Files.write(script, source.getBytes(StandardCharsets.UTF_8));

        Process seed =
                new ProcessBuilder("sh", script.toString(), repo.toString(), baseFile.toString())
                        .redirectErrorStream(true)
                        .start();
        String seedOutput =
                new String(seed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(seed.waitFor()).as("프로브 저장소 준비 실패:%n%s", seedOutput).isZero();

        return new Fixture(
                repo.toFile(), Files.readString(baseFile, StandardCharsets.UTF_8).trim());
    }

    private record Fixture(File repo, String base) {}

    private static boolean isWatched(String path) {
        return WATCHED_PREFIXES.stream().anyMatch(path::startsWith) || WATCHED_FILES.contains(path);
    }

    private static GitResult git(String... args) {
        return git(null, args);
    }

    /**
     * {@code dir}이 null이면 현재 작업 디렉터리에서 돈다. 표준 출력은 가공하지 않은 채로도
     * 들고 있는다 — {@code -z} 출력은 줄 단위로 자르면 안 되기 때문이다.
     */
    private static GitResult git(File dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process =
                    new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
            String stdout =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new StringReader(stdout))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
            return new GitResult(process.waitFor(), lines, stdout);
        } catch (IOException | InterruptedException e) {
            return new GitResult(-1, List.of(), "");
        }
    }

    private record GitResult(int exitCode, List<String> lines, String stdout) {}
}
