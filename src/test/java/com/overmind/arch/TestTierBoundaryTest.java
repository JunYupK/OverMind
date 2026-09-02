package com.overmind.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. 스펙 §6.2 — L1(태그 없음)에서 Spring 컨텍스트나 Docker 컨테이너를 띄우지 않는다.
 *
 * <p>스펙은 이 금지를 "네이밍 규칙과 ArchUnit으로 강제한다"고 적어 두었지만 둘 중 어느 것도
 * 존재하지 않았다. {@code LayerDependencyTest}는 {@code ImportOption.DoNotIncludeTests}로
 * 임포트하므로 테스트 클래스를 아예 보지 못한다. 그래서 소스를 직접 읽는다.
 *
 * <p>금지하는 이유: 컨텍스트가 뜨는 순간 단위 테스트가 아니다. 초 단위 피드백이 깨지면
 * 루프 [3]→[2] 반송이 실용성을 잃고, Docker 없이는 {@code ./gradlew verify}가 아예 돌지 않는다.
 * {@code integration}/{@code evaluation} 태그가 붙은 테스트는 애초에 컨텍스트를 띄우는
 * 계층이므로 허용한다.
 *
 * <h2>왜 줄 단위 정규식을 버렸나</h2>
 *
 * <p>예전 구현은 파일의 각 줄에 {@code ^}로 앵커한 정규식을 걸었다. 그래서 타입이 등록되려면
 * 선언이 0열에서 시작하고 상속 절이 <b>같은 물리적 줄</b>에 있어야 했다. 포매터가 긴 선언을
 * 접기만 해도 상속 사슬이 끊겨 게이트가 통째로 무력화됐다. 실제로 컨텍스트와 컨테이너가
 * 초록색 {@code ./gradlew test} 안에서 뜨는 것이 재현됐다. 우회로는 다섯 갈래였다.
 *
 * <ol>
 *   <li>상속 절 줄바꿈 — 상위 클래스 링크 소실
 *   <li>메서드에 붙인 태그 — 태그를 파일 전체에서 모아 클래스 태그로 오인
 *   <li>완전 수식 애노테이션 — 단순 이름만 보던 패턴이 놓침
 *   <li>클래스 선언과 같은 줄의 애노테이션 — 줄 앵커가 깨져 타입 자체가 미등록
 *   <li>메타 애노테이션 — 커스텀 애노테이션 타입이 이름을 숨김
 * </ol>
 *
 * <p>지금은 파일을 통째로 읽어 <b>주석과 문자열 리터럴을 같은 길이의 공백으로 지운 뒤</b>
 * 중괄호/괄호 깊이를 추적하며 최상위 타입을 찾는다. 깊이 0에서 발견한 선언만 최상위이고,
 * 애노테이션과 수식자는 선언 직전 경계까지의 <b>헤더 구간</b>에서만 읽는다. 그래서 메서드에
 * 붙은 태그는 클래스 태그가 되지 않는다. 상속 절은 선언 이름 끝부터 본문 여는 중괄호까지를
 * 통째로 보므로 줄바꿈에 영향받지 않는다.
 *
 * <p>주석과 문자열을 먼저 지우기 때문에 <b>이 파일 자신의 문서 주석과 검증용 예제 문자열은
 * 검사 대상이 아니다.</b> 예전 구현이 줄 앞머리 앵커로 흉내 내던 성질을 구조적으로 보장한다.
 *
 * <p>한계: 타입 이름은 단순 이름으로만 식별한다. 서로 다른 패키지에 같은 단순 이름의 테스트
 * 타입이 생기면 뒤에 읽은 것이 앞을 덮는다. 이 저장소의 테스트 작성 규약이 그렇다.
 */
class TestTierBoundaryTest {

    private static final Path TEST_ROOT = Path.of("src/test/java");

    /** 컨텍스트를 띄우는 애노테이션의 단순 이름. 메타 애노테이션을 통해 전이된다. */
    private static final Set<String> CONTEXT_ANNOTATIONS = Set.of("SpringBootTest");

    /** 컨텍스트를 띄워도 되는 계층. */
    private static final Set<String> CONTEXT_ALLOWED_TAGS = Set.of("integration", "evaluation");

    /**
     * 타입 헤더/본문에 나타나면 그 타입이 Docker를 켠다고 본다. 스펙 §6.2의 문언 밖이지만
     * 막으려는 해악(초 단위 피드백 상실, verify가 Docker에 묶임)은 같다.
     */
    private static final Pattern CONTAINER_SYMBOL =
            Pattern.compile(
                    "\\b(?:GenericContainer|PostgreSQLContainer|MySQLContainer|MariaDBContainer"
                            + "|KafkaContainer|DockerComposeContainer|ComposeContainer"
                            + "|Testcontainers)\\b");

    private static final Pattern TYPE_DECL =
            Pattern.compile("(?<![\\w.$])(@\\s*interface|class|interface|enum|record)\\s+(\\w+)");

    private static final Pattern ANNOTATION = Pattern.compile("@\\s*([\\w.]+)");

    private static final Pattern SUPER_LIST =
            Pattern.compile("\\b(?:extends|implements)\\s+((?:[\\w.]+\\s*,\\s*)*[\\w.]+)");

    private static final Pattern GENERICS = Pattern.compile("<[^<>]*>");

    private static final Pattern STRING_ARG = Pattern.compile("\"([^\"]*)\"");

    private static final Pattern ABSTRACT = Pattern.compile("\\babstract\\b");

    /** 헤더 구간에서 읽은 애노테이션 하나. value는 첫 문자열 인자다(없으면 null). */
    private record Anno(String name, String value) {}

    private record TestType(
            String name,
            String kind,
            boolean isAbstract,
            List<String> superNames,
            List<Anno> annotations,
            boolean startsContainer,
            Path file) {}

    // ---------- 게이트 ----------

    @Test
    void untagged_tests_do_not_boot_spring() throws IOException {
        assertThat(violations(scan()))
                .as(
                        "스펙 §6.2: L1(태그 없는 테스트)은 Spring 컨텍스트도 Docker 컨테이너도 띄우지 "
                                + "않습니다. 이 테스트는 `./gradlew test`에서 돌고, 컨텍스트 기동과 컨테이너가 "
                                + "그 안으로 들어오면 초 단위 피드백이 깨집니다. "
                                + "L2로 내리려면 integration, L3면 evaluation 태그를 붙이세요")
                .isEmpty();
    }

    /**
     * 스캐너가 조용히 눈이 머는 것을 막는다. 파일마다 최상위 타입이 하나도 안 나오면 파싱이
     * 썩은 것이고, 알려진 타입의 성질(상속·태그·컨테이너)이 어긋나도 마찬가지다.
     */
    @Test
    void the_scan_actually_sees_the_test_tree() throws IOException {
        assertThat(TEST_ROOT).isDirectory();

        List<Path> files = sourceFiles();
        assertThat(files).as("src/test/java에 자바 소스가 하나도 없습니다").isNotEmpty();

        List<String> blind = new ArrayList<>();
        for (Path file : files) {
            Map<String, TestType> single = new LinkedHashMap<>();
            parseInto(Files.readString(file), file, single);
            if (single.isEmpty()) {
                blind.add(file.toString());
            }
        }
        assertThat(blind)
                .as("최상위 타입 선언을 하나도 찾지 못한 파일이 있습니다. 이 검사의 파싱이 깨졌습니다")
                .isEmpty();

        Map<String, TestType> types = scan();
        assertThat(types)
                .as("스캐너가 알려진 앵커 타입을 놓쳤습니다 (이름이 바뀌었다면 이 앵커도 갱신하세요)")
                .containsKeys("FlywayMigrationTest", "PostgresTestBase");

        TestType flyway = types.get("FlywayMigrationTest");
        assertThat(flyway.superNames()).as("상속 절 파싱").contains("PostgresTestBase");
        assertThat(declaresContextAnnotation(flyway, types)).as("컨텍스트 애노테이션 인식").isTrue();
        assertThat(hasAllowedTag(flyway, types)).as("클래스 태그 인식").isTrue();

        TestType base = types.get("PostgresTestBase");
        assertThat(base.isAbstract()).as("abstract 수식자 인식").isTrue();
        assertThat(base.startsContainer()).as("컨테이너 심볼 인식").isTrue();
    }

    /** 실제로 재현됐던 우회로 다섯 갈래를 스캐너에 직접 먹인다. 회귀하면 여기서 먼저 깨진다. */
    @Test
    void known_evasions_are_caught() {
        // 1) 상속 절 줄바꿈 — 포매터가 저절로 만들어 내는 모양
        assertThat(
                        violate(
                                "@SpringBootTest\nabstract class WrapBase {}",
                                """
                                class WrapChildTest
                                        extends WrapBase {
                                    @Test void t() {}
                                }
                                """))
                .as("우회 1 — 줄바꿈된 extends")
                .anyMatch(v -> v.contains("WrapChildTest"));

        // 2) 타입이 아니라 메서드에 붙인 태그
        assertThat(
                        violate(
                                """
                                @SpringBootTest
                                class MethodTagTest {
                                    @Tag("integration")
                                    @Test void tagged() {}
                                    @Test void untagged() {}
                                }
                                """))
                .as("우회 2 — 메서드 레벨 태그")
                .anyMatch(v -> v.contains("MethodTagTest"));

        // 3) 완전 수식 애노테이션
        assertThat(
                        violate(
                                """
                                @org.springframework.boot.test.context.SpringBootTest
                                class FqnTest {
                                    @Test void t() {}
                                }
                                """))
                .as("우회 3 — 완전 수식 애노테이션")
                .anyMatch(v -> v.contains("FqnTest"));

        // 4) 클래스 선언과 같은 줄의 애노테이션
        assertThat(violate("@SpringBootTest class SameLineTest { @Test void t() {} }"))
                .as("우회 4 — 같은 줄 애노테이션")
                .anyMatch(v -> v.contains("SameLineTest"));

        // 5) 메타 애노테이션 — 커스텀 애노테이션 타입이 이름을 숨긴다
        assertThat(
                        violate(
                                "@SpringBootTest\npublic @interface BootsContext {}",
                                """
                                @BootsContext
                                class MetaTest {
                                    @Test void t() {}
                                }
                                """))
                .as("우회 5 — 메타 애노테이션")
                .anyMatch(v -> v.contains("MetaTest"));

        // 인접 사례 — 애노테이션도 태그도 없이 상속만으로 컨테이너를 켜는 경우
        assertThat(
                        violate(
                                """
                                abstract class ContainerBase {
                                    static final PostgreSQLContainer<?> C =
                                            new PostgreSQLContainer<>("pg");
                                }
                                """,
                                "class ContainerChildTest extends ContainerBase { @Test void t() {} }"))
                .as("인접 — 태그 없이 컨테이너만 띄우는 클래스")
                .anyMatch(v -> v.contains("ContainerChildTest"));
    }

    /** 오탐은 미탐과 같은 무게의 결함이다. 정당한 모양이 걸리지 않는지 같이 못 박는다. */
    @Test
    void legitimate_shapes_are_not_flagged() {
        // 문서 주석과 문자열 리터럴에 이름이 나온다고 컨텍스트가 뜨는 것은 아니다
        assertThat(
                        violate(
                                """
                                /** 문서 주석 안의 @SpringBootTest 언급. */
                                class PlainTest {
                                    String mentioned = "@SpringBootTest";
                                    // 주석 안의 @SpringBootTest 언급
                                    @Test void t() {}
                                }
                                """))
                .as("주석/문자열 언급은 오탐이 아니어야 한다")
                .isEmpty();

        // L2 — 태그가 붙어 있으면 줄바꿈된 상속이든 뭐든 허용이다
        assertThat(
                        violate(
                                "@SpringBootTest\nabstract class OkBase {}",
                                """
                                @Tag("integration")
                                class TaggedChildTest
                                        extends OkBase {
                                    @Test void t() {}
                                }
                                """))
                .as("태그가 붙은 L2는 통과해야 한다")
                .isEmpty();

        // 메타 애노테이션이 태그를 실어 나르는 경우도 허용이다
        assertThat(
                        violate(
                                "@SpringBootTest\n@Tag(\"integration\")\npublic @interface L2Test {}",
                                """
                                @L2Test
                                class MetaTaggedTest {
                                    @Test void t() {}
                                }
                                """))
                .as("태그를 실어 나르는 메타 애노테이션은 통과해야 한다")
                .isEmpty();

        // 추상 베이스 자체는 실행되지 않는다
        assertThat(violate("@SpringBootTest\nabstract class LoneBase {}"))
                .as("추상 베이스 단독은 위반이 아니다")
                .isEmpty();

        // 애노테이션 인자의 배열 리터럴이 중괄호 깊이를 흔들면 안 된다
        assertThat(
                        violate(
                                """
                                @Tag("integration")
                                @SpringBootTest(classes = {App.class, Extra.class})
                                class ArrayArgTest {
                                    @Test void t() {}
                                }
                                """))
                .as("애노테이션 인자의 배열 리터럴은 파싱을 깨뜨리지 않아야 한다")
                .isEmpty();
    }

    // ---------- 판정 ----------

    private static List<String> violations(Map<String, TestType> types) {
        List<String> out = new ArrayList<>();
        for (TestType type : types.values()) {
            if (!"class".equals(type.kind()) || type.isAbstract()) {
                continue;
            }
            boolean spring = walkUp(type, types, t -> declaresContextAnnotation(t, types));
            boolean docker = walkUp(type, types, TestType::startsContainer);
            if (!spring && !docker) {
                continue;
            }
            if (walkUp(type, types, t -> hasAllowedTag(t, types))) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (spring) {
                reasons.add("Spring 컨텍스트");
            }
            if (docker) {
                reasons.add("Docker 컨테이너");
            }
            out.add(
                    type.file()
                            + " → "
                            + type.name()
                            + " 가 L1에서 "
                            + String.join("와 ", reasons)
                            + "를 띄웁니다");
        }
        return out;
    }

    private static boolean declaresContextAnnotation(TestType type, Map<String, TestType> types) {
        for (Anno anno : type.annotations()) {
            if (annotationBoots(anno.name(), types, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotationBoots(
            String name, Map<String, TestType> types, Set<String> seen) {
        if (!seen.add(name)) {
            return false;
        }
        if (CONTEXT_ANNOTATIONS.contains(name)) {
            return true;
        }
        TestType decl = types.get(name);
        if (decl == null || !"@interface".equals(decl.kind())) {
            return false;
        }
        for (Anno anno : decl.annotations()) {
            if (annotationBoots(anno.name(), types, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAllowedTag(TestType type, Map<String, TestType> types) {
        Set<String> tags = new HashSet<>();
        collectTags(type.annotations(), types, new HashSet<>(), tags);
        return tags.stream().anyMatch(CONTEXT_ALLOWED_TAGS::contains);
    }

    private static void collectTags(
            List<Anno> annotations,
            Map<String, TestType> types,
            Set<String> seen,
            Set<String> out) {
        for (Anno anno : annotations) {
            if ("Tag".equals(anno.name()) && anno.value() != null) {
                out.add(anno.value());
            }
            TestType decl = types.get(anno.name());
            if (decl != null && "@interface".equals(decl.kind()) && seen.add(anno.name())) {
                collectTags(decl.annotations(), types, seen, out);
            }
        }
    }

    /** 자기 자신부터 상위 타입(extends/implements)까지 훑는다. 순환은 seen으로 끊는다. */
    private static boolean walkUp(
            TestType start, Map<String, TestType> types, Predicate<TestType> predicate) {
        Deque<TestType> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            TestType current = stack.pop();
            if (!seen.add(current.name())) {
                continue;
            }
            if (predicate.test(current)) {
                return true;
            }
            for (String superName : current.superNames()) {
                TestType up = types.get(superName);
                if (up != null) {
                    stack.push(up);
                }
            }
        }
        return false;
    }

    // ---------- 스캔 ----------

    private static Map<String, TestType> scan() throws IOException {
        Map<String, TestType> types = new LinkedHashMap<>();
        if (!Files.isDirectory(TEST_ROOT)) {
            return types;
        }
        for (Path file : sourceFiles()) {
            parseInto(Files.readString(file), file, types);
        }
        return types;
    }

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith("package-info"))
                    .filter(p -> !p.getFileName().toString().startsWith("module-info"))
                    .sorted()
                    .toList();
        }
    }

    /** 테스트용 — 소스 조각들을 한 맵에 파싱해 위반 목록을 낸다. */
    private static List<String> violate(String... sources) {
        Map<String, TestType> types = new LinkedHashMap<>();
        for (int i = 0; i < sources.length; i++) {
            parseInto(sources[i], Path.of("probe-" + i + ".java"), types);
        }
        return violations(types);
    }

    private static void parseInto(String source, Path file, Map<String, TestType> into) {
        String scrubbed = scrub(source);
        int n = scrubbed.length();

        // 중괄호/괄호 깊이. 값은 "i번째 문자를 읽기 직전"의 깊이다.
        // 애노테이션 인자의 배열 리터럴이 깊이를 흔들지 않도록 괄호 안에서는 중괄호를 세지 않는다.
        int[] brace = new int[n + 1];
        int[] paren = new int[n + 1];
        int braceDepth = 0;
        int parenDepth = 0;
        for (int i = 0; i < n; i++) {
            brace[i] = braceDepth;
            paren[i] = parenDepth;
            char c = scrubbed.charAt(i);
            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (parenDepth == 0 && c == '{') {
                braceDepth++;
            } else if (parenDepth == 0 && c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            }
        }
        brace[n] = braceDepth;
        paren[n] = parenDepth;

        Matcher decl = TYPE_DECL.matcher(scrubbed);
        while (decl.find()) {
            int start = decl.start();
            if (brace[start] != 0 || paren[start] != 0) {
                continue; // 중첩 타입이거나 애노테이션 인자 속이다
            }
            String kind = decl.group(1).startsWith("@") ? "@interface" : decl.group(1);
            String name = decl.group(2);

            // 헤더 구간 — 선언 직전의 경계까지. 애노테이션과 수식자는 여기서만 읽는다.
            // 메서드에 붙은 태그는 중괄호 깊이 1이라 여기에 들어올 수 없다.
            int headerStart = 0;
            for (int k = start - 1; k >= 0; k--) {
                if (paren[k] > 0) {
                    continue;
                }
                char c = scrubbed.charAt(k);
                if (c == ';' || c == '{' || c == '}') {
                    headerStart = k + 1;
                    break;
                }
            }
            String header = scrubbed.substring(headerStart, start);

            // 본문 여는 중괄호까지가 시그니처다 — 줄바꿈 여부와 무관하게 상속 절이 다 들어온다.
            int bodyOpen = n;
            for (int k = decl.end(); k < n; k++) {
                if (paren[k] == 0 && scrubbed.charAt(k) == '{') {
                    bodyOpen = k;
                    break;
                }
            }
            String signature = stripGenerics(scrubbed.substring(decl.end(), bodyOpen));

            int bodyEnd = n;
            if (bodyOpen < n) {
                int inner = brace[bodyOpen] + 1;
                for (int k = bodyOpen + 1; k < n; k++) {
                    if (paren[k] == 0 && scrubbed.charAt(k) == '}' && brace[k] == inner) {
                        bodyEnd = k;
                        break;
                    }
                }
            }
            String body = bodyOpen < n ? scrubbed.substring(bodyOpen, bodyEnd) : "";

            List<Anno> annotations = new ArrayList<>();
            Matcher am = ANNOTATION.matcher(header);
            while (am.find()) {
                annotations.add(
                        new Anno(
                                simpleName(am.group(1)),
                                firstStringArg(scrubbed, source, headerStart + am.end(), paren)));
            }

            List<String> supers = new ArrayList<>();
            Matcher sm = SUPER_LIST.matcher(signature);
            while (sm.find()) {
                for (String part : sm.group(1).split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        supers.add(simpleName(trimmed));
                    }
                }
            }

            boolean container =
                    CONTAINER_SYMBOL.matcher(header).find() || CONTAINER_SYMBOL.matcher(body).find();

            into.put(
                    name,
                    new TestType(
                            name,
                            kind,
                            ABSTRACT.matcher(header).find(),
                            supers,
                            annotations,
                            container,
                            file));
        }
    }

    /**
     * 애노테이션 인자의 첫 문자열 리터럴. 위치는 지워진 소스로 찾고 값은 원본에서 읽는다 —
     * 그래야 태그 값을 잃지 않으면서도 주석/문자열이 구조 파싱을 흔들지 않는다.
     */
    private static String firstStringArg(String scrubbed, String source, int from, int[] paren) {
        int n = scrubbed.length();
        int i = from;
        while (i < n && Character.isWhitespace(scrubbed.charAt(i))) {
            i++;
        }
        if (i >= n || scrubbed.charAt(i) != '(') {
            return null;
        }
        int depth = paren[i];
        int close = -1;
        for (int k = i + 1; k < n; k++) {
            if (scrubbed.charAt(k) == ')' && paren[k] == depth + 1) {
                close = k;
                break;
            }
        }
        if (close < 0) {
            return null;
        }
        Matcher m = STRING_ARG.matcher(source.substring(i + 1, close));
        return m.find() ? m.group(1) : null;
    }

    /**
     * 주석과 문자열/문자 리터럴을 같은 길이의 공백으로 바꾼다. 줄바꿈은 남겨 오프셋을 보존한다.
     * 이 단계 덕분에 이 파일 자신의 문서 주석과 예제 문자열이 검사에 걸리지 않는다.
     */
    private static String scrub(String source) {
        char[] out = source.toCharArray();
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = blank(out, i, end < 0 ? n : end + 2);
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = blank(out, i, end < 0 ? n : end + 3);
            } else if (c == '"' || c == '\'') {
                int k = i + 1;
                while (k < n && source.charAt(k) != c && source.charAt(k) != '\n') {
                    if (source.charAt(k) == '\\') {
                        k++;
                    }
                    k++;
                }
                i = blank(out, i, Math.min(k + 1, n));
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int blank(char[] out, int from, int to) {
        for (int k = from; k < to; k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
        return to;
    }

    private static String stripGenerics(String signature) {
        String previous;
        String current = signature;
        do {
            previous = current;
            current = GENERICS.matcher(current).replaceAll(" ");
        } while (!current.equals(previous));
        return current;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
