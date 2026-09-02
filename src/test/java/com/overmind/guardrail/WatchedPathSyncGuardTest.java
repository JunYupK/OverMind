package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 감시 경로 목록이 세 곳에 있는데 셋이 갈라지지 않게 한다.
 *
 * <p><b>진실의 원천은 {@link LogUpdatedGuardTest}의 {@code WATCHED_PREFIXES} /
 * {@code WATCHED_FILES}다.</b> {@code AGENTS.md} 절대 규칙 3과
 * {@code docs/harness/40-guardrails.md}의 표는 사본이고, 이 검사가 둘을 원천과 대조한다.
 *
 * <p>왜 필요한가 — 이 저장소는 같은 결함을 두 번 겪었다. 전수 리뷰의 I-5(문서는 "같은
 * 커밋에"라고 하는데 가드는 범위를 검사했다)와 {@code settings.gradle.kts} 누락이
 * 전부 이 형태였다. <b>주석으로 "셋이 같아야 한다"고 적어 두는 것은 게이트가 아니다.</b>
 *
 * <p>사본은 문서이므로 표현이 코드와 다르다. {@code src/**}처럼 glob 별표가 붙거나
 * 표 칸에 여러 개가 들어간다. 그래서 마커 블록 안의 백틱 토큰을 모아 별표를 떼고 비교한다.
 *
 * <p><b>파싱 실패는 통과가 아니라 실패다.</b> 마커가 없거나 블록이 비면 그 자체로 실패한다 —
 * 조용히 건너뛰는 검사는 이 저장소가 이미 여러 번 당한 형태다.
 */
@Tag("guardrail")
class WatchedPathSyncGuardTest {

    private static final String BEGIN = "<!-- watched-paths:begin";
    private static final String END = "<!-- watched-paths:end";

    /** 백틱으로 감싼 토큰. 문서의 표현 그대로 뽑아 온다. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    private static Set<String> expected() {
        return Stream.concat(
                        LogUpdatedGuardTest.WATCHED_PREFIXES.stream(),
                        LogUpdatedGuardTest.WATCHED_FILES.stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void agents_md_copy_matches_the_guard() throws IOException {
        assertThat(pathsInMarkedBlock(Path.of("AGENTS.md")))
                .as(
                        "AGENTS.md 절대 규칙 3의 감시 경로 목록이 LogUpdatedGuardTest와 다릅니다. "
                                + "가드 코드가 진실의 원천이므로 문서를 코드에 맞추세요")
                .isEqualTo(expected());
    }

    @Test
    void guardrails_doc_copy_matches_the_guard() throws IOException {
        assertThat(pathsInMarkedBlock(Path.of("docs/harness/40-guardrails.md")))
                .as(
                        "docs/harness/40-guardrails.md의 감시 경로 표가 LogUpdatedGuardTest와 "
                                + "다릅니다. 가드 코드가 진실의 원천이므로 문서를 코드에 맞추세요")
                .isEqualTo(expected());
    }

    /**
     * 파서가 실제로 무언가를 보고 있는지 확인한다. 마커를 지우거나 블록을 비우면
     * 위 두 검사는 "빈 집합 == 빈 집합"으로 통과할 수 있다. 그 경로를 막는다.
     */
    @Test
    void the_guard_list_itself_is_not_empty() {
        assertThat(expected())
                .as("감시 경로 목록이 비었습니다. 가드가 아무 경로도 안 보고 있습니다")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    /**
     * 마커 블록에서 백틱 토큰을 모아 정규화한다.
     *
     * <p>정규화는 문서 표현을 코드 표현으로 되돌리는 것뿐이다 — 뒤에 붙은 glob 별표를 뗀다.
     * {@code src/**} → {@code src/}. 그 밖에는 손대지 않는다.
     */
    private static Set<String> pathsInMarkedBlock(Path file) throws IOException {
        assertThat(file).as("문서 %s 가 없습니다", file).isRegularFile();
        String text = Files.readString(file, StandardCharsets.UTF_8);

        int begin = text.indexOf(BEGIN);
        int end = text.indexOf(END);
        assertThat(begin)
                .as("%s 에 '%s' 마커가 없습니다. 사본을 기계가 읽을 수 없으면 대조도 못 합니다", file, BEGIN)
                .isNotNegative();
        assertThat(end)
                .as("%s 에 '%s' 마커가 없습니다", file, END)
                .isGreaterThan(begin);

        // begin 마커 줄 자체는 제외한다 — 그 주석 안의 백틱까지 세면 안 된다.
        int blockStart = text.indexOf("-->", begin) + 3;
        String block = text.substring(blockStart, end);

        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = BACKTICKED.matcher(block);
        while (matcher.find()) {
            found.add(stripGlob(matcher.group(1).trim()));
        }

        assertThat(found)
                .as("%s 의 마커 블록에서 경로를 하나도 찾지 못했습니다. 블록이 비었거나 파싱이 깨졌습니다", file)
                .isNotEmpty();
        return found;
    }

    private static String stripGlob(String token) {
        String stripped = token;
        while (stripped.endsWith("*")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    /** 정규화가 무엇을 하는지 못 박는다. 이 규칙이 바뀌면 위 두 검사의 의미가 바뀐다. */
    @Test
    void glob_stars_are_stripped_and_nothing_else_is() {
        assertThat(List.of(stripGlob("src/**"), stripGlob("AGENTS.md"), stripGlob("docs/arch/**")))
                .containsExactly("src/", "AGENTS.md", "docs/arch/");
    }
}
