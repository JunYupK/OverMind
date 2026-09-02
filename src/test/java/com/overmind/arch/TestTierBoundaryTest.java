package com.overmind.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. 스펙 §6.2 — L1(태그 없음)에서 {@code @SpringBootTest}를 쓰지 않는다.
 *
 * <p>스펙은 이 금지를 "네이밍 규칙과 ArchUnit으로 강제한다"고 적어 두었지만 둘 중 어느 것도
 * 존재하지 않았다. {@code LayerDependencyTest}는
 * {@code ImportOption.DoNotIncludeTests}로 임포트하므로 테스트 클래스를 아예 보지 못한다.
 * 그래서 {@code ProviderNameLeakTest}와 같은 방식으로 소스를 직접 읽는다.
 *
 * <p>금지하는 이유: Spring 컨텍스트가 뜨는 순간 단위 테스트가 아니다. 초 단위 피드백이 깨지면
 * 루프 [3]→[2] 반송이 실용성을 잃는다. {@code integration}/{@code evaluation} 태그가 붙은
 * 테스트는 애초에 컨텍스트를 띄우는 계층이므로 허용한다.
 *
 * <p>상속으로 우회하는 것도 막는다 — 부모가 컨텍스트를 띄우면 자식도 띄운다. 태그도 같은 방식으로
 * 상속 사슬을 따라 올라가며 본다.
 *
 * <p>한계: 파일 하나에 최상위 타입 하나를 가정하고 {@code @Tag}는 파일 단위로 본다.
 * 이 저장소의 테스트 작성 규약이 그렇다. 중첩 클래스마다 태그를 다르게 붙이기 시작하면
 * 이 검사를 파서 기반으로 바꿔야 한다.
 */
class TestTierBoundaryTest {

    private static final Path TEST_ROOT = Path.of("src/test/java");

    private static final Pattern TOP_LEVEL_CLASS =
            Pattern.compile(
                    "^(?:(?:public|final|abstract|sealed|non-sealed)\\s+)*"
                            + "(?:class|interface)\\s+(\\w+)"
                            + "(?:\\s*<[^>]*>)?"
                            + "(?:\\s+extends\\s+([\\w.]+))?");

    private static final Pattern TAG = Pattern.compile("@Tag\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    /**
     * 애노테이션으로 실제로 붙은 경우만 잡는다. 줄을 trim했을 때 {@code @SpringBootTest}로
     * 시작해야 한다 — javadoc의 {@code {@code ...}}나 실패 메시지 문자열에 이름이 나온다고
     * 그 파일이 컨텍스트를 띄우는 것은 아니다. (이 파일 자신이 그 경우다.)
     */
    private static final Pattern SPRING_BOOT_TEST_ANNOTATION =
            Pattern.compile("^\\s*@SpringBootTest\\b");

    /** 컨텍스트를 띄워도 되는 계층. */
    private static final Set<String> CONTEXT_ALLOWED_TAGS = Set.of("integration", "evaluation");

    private record TestType(
            String name,
            String superName,
            boolean isAbstract,
            boolean declaresSpringBootTest,
            Set<String> tags,
            Path file) {}

    @Test
    void untagged_tests_do_not_boot_spring() throws IOException {
        Map<String, TestType> types = scan();
        List<String> violations = new ArrayList<>();

        for (TestType type : types.values()) {
            if (type.isAbstract()) {
                continue;
            }
            if (!bootsSpring(type, types)) {
                continue;
            }
            if (hasContextAllowedTag(type, types)) {
                continue;
            }
            violations.add(
                    type.file()
                            + " → "
                            + type.name()
                            + (type.declaresSpringBootTest()
                                    ? " 가 @SpringBootTest를 직접 붙였습니다"
                                    : " 가 @SpringBootTest를 붙인 상위 클래스를 상속합니다"));
        }

        assertThat(violations)
                .as(
                        "스펙 §6.2: L1(태그 없는 테스트)은 Spring 컨텍스트를 띄우지 않습니다. "
                                + "이 테스트는 `./gradlew test`에서 돌고, 컨텍스트 기동과 Docker 컨테이너가 "
                                + "그 안으로 들어오면 초 단위 피드백이 깨집니다. "
                                + "L2로 내리려면 @Tag(\"integration\"), L3면 @Tag(\"evaluation\")을 붙이세요")
                .isEmpty();
    }

    /** 최상위 타입 선언이 하나도 안 잡히면 정규식이 썩은 것이다. 조용히 0건 통과하지 않게 막는다. */
    @Test
    void the_scan_actually_sees_the_test_tree() throws IOException {
        assertThat(TEST_ROOT).isDirectory();
        assertThat(scan())
                .as("src/test/java에서 최상위 타입 선언을 하나도 찾지 못했습니다. 이 검사의 파싱이 깨졌습니다")
                .isNotEmpty();
    }

    private static boolean bootsSpring(TestType type, Map<String, TestType> types) {
        return walkUp(type, types, TestType::declaresSpringBootTest);
    }

    private static boolean hasContextAllowedTag(TestType type, Map<String, TestType> types) {
        return walkUp(
                type,
                types,
                t -> t.tags().stream().anyMatch(CONTEXT_ALLOWED_TAGS::contains));
    }

    private static boolean walkUp(
            TestType start,
            Map<String, TestType> types,
            java.util.function.Predicate<TestType> predicate) {
        Set<String> seen = new HashSet<>();
        TestType current = start;
        while (current != null && seen.add(current.name())) {
            if (predicate.test(current)) {
                return true;
            }
            current = current.superName() == null ? null : types.get(simpleName(current.superName()));
        }
        return false;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static Map<String, TestType> scan() throws IOException {
        Map<String, TestType> types = new LinkedHashMap<>();
        if (!Files.isDirectory(TEST_ROOT)) {
            return types;
        }
        try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                String body = String.join("\n", lines);

                Set<String> tags = new HashSet<>();
                Matcher tagMatcher = TAG.matcher(body);
                while (tagMatcher.find()) {
                    tags.add(tagMatcher.group(1));
                }
                boolean springBootTest =
                        lines.stream()
                                .anyMatch(l -> SPRING_BOOT_TEST_ANNOTATION.matcher(l).find());

                for (String line : lines) {
                    Matcher m = TOP_LEVEL_CLASS.matcher(line);
                    if (m.find()) {
                        types.put(
                                m.group(1),
                                new TestType(
                                        m.group(1),
                                        m.group(2),
                                        line.contains("abstract "),
                                        springBootTest,
                                        tags,
                                        file));
                        break;
                    }
                }
            }
        }
        return types;
    }
}
