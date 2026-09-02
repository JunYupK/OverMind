import java.io.File
import java.security.MessageDigest

plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.overmind"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}


dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------- 0건 실행 바닥 (zero-test floor) ----------
// 게이트가 "아무것도 실행하지 않고 성공"하는 경로가 두 개 있었다.
//
//  1) 테스트 소스가 통째로 사라지면 Test 태스크가 NO-SOURCE가 되어 액션이 아예 안 돈다.
//     NO-SOURCE는 실패가 아니라 스킵이므로 verify가 2초 만에 초록이 된다.
//  2) `isFailOnNoMatchingTests = false` 때문에 태그 필터가 하나도 안 걸려도 초록이다.
//     `@Tag("guardrail")`을 `guardrails`로 오타 내면 가드레일 4종이 통째로 사라진 채
//     `./gradlew guardrails`가 초록이 된다.
//
// 두 경우 모두 CI 로그가 정상 실행과 구별되지 않는다. 그래서 Test 태스크 바깥에
// 별도 검사 태스크를 붙인다 — 태스크가 스킵되면 doLast도 같이 스킵되므로
// 바닥 검사를 Test 태스크 안에 둘 수 없다.
//
// `isFailOnNoMatchingTests = false`는 그대로 둔다. 그것을 안전하게 만드는 것이 이 바닥이다.
// `evaluationTest`는 제외한다 — 마일스톤에 따라 정당하게 비어 있다.

val testsuiteCount = Regex("""<testsuite[^>]*\stests="(\d+)"""")

fun withZeroTestFloor(testTask: TaskProvider<Test>): TaskProvider<Task> {
    val resultsDir = testTask.flatMap { it.reports.junitXml.outputLocation }
    val floor =
        tasks.register("${testTask.name}NotEmpty") {
            group = "verification"
            description = "${testTask.name}가 실제로 테스트를 실행했는지 확인한다 (0건 = 실패)"
            dependsOn(testTask)
            outputs.upToDateWhen { false }
            doLast {
                val dir = resultsDir.get().asFile
                val xml = dir.listFiles { f -> f.extension == "xml" }?.toList() ?: emptyList()
                val count =
                    xml.sumOf { f ->
                        testsuiteCount.findAll(f.readText()).sumOf { it.groupValues[1].toInt() }
                    }
                if (count == 0) {
                    throw GradleException(
                        "[floor] ${testTask.name}가 테스트를 0건 실행했습니다. " +
                            "게이트가 아무것도 검사하지 않고 통과했다는 뜻입니다. " +
                            "테스트 소스가 사라졌거나(NO-SOURCE) 태그 필터가 아무것도 못 잡았습니다 " +
                            "(결과 디렉터리: $dir). " +
                            "태그 철자와 src/test 트리를 확인하세요."
                    )
                }
                logger.lifecycle("[floor] ${testTask.name} — 테스트 ${count}건 실행 확인")
            }
        }
    testTask.configure { finalizedBy(floor) }
    return floor
}

// ---------- 테스트 계층 ----------
// L1 = 태그 없음 / L2 = integration / L3 = evaluation / 가드레일 = guardrail

val unitTest = tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration", "evaluation", "guardrail")
    }
    testLogging { events("failed") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "L2 — Testcontainers + LLM 픽스처 재생"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    filter { isFailOnNoMatchingTests = false }
    systemProperty("overmind.llm.record", System.getProperty("overmind.llm.record", "false"))
    shouldRunAfter(tasks.named("test"))
    testLogging { events("failed") }
}

val evaluationTest = tasks.register<Test>("evaluationTest") {
    group = "verification"
    description = "L3 — 실제 LLM 호출. 비용이 발생한다"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("evaluation") }
    filter { isFailOnNoMatchingTests = false }
    testLogging { events("failed", "passed") }
}

val guardrailTest = tasks.register<Test>("guardrailTest") {
    group = "verification"
    description = "가드레일 검사"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("guardrail") }
    filter { isFailOnNoMatchingTests = false }
    systemProperty(
        "overmind.guardrail.baseRef",
        (project.findProperty("baseRef") ?: "origin/master").toString()
    )
    outputs.upToDateWhen { false }
    testLogging { events("failed", "skipped") }
    // JUnit5 assumeTrue()로 건너뛴 테스트는 Gradle의 TestResult에 사유를 실어 나르지 않는다
    // (result.exceptions가 항상 비어 있다). JUnit XML 리포트에는 사유가 그대로 남으므로
    // 실행 후 그 파일을 읽어 콘솔에 눈에 띄게 다시 찍는다 — CI 로그만 보고도
    // 어떤 baseRef가 안 풀렸는지 알 수 있어야 한다.
    doLast {
        val resultsDir = reports.junitXml.outputLocation.get().asFile
        if (resultsDir.isDirectory) {
            val skipMessage = Regex("<skipped message=\"([^\"]*)\"")
            resultsDir.listFiles { f -> f.extension == "xml" }
                ?.forEach { xmlFile ->
                    skipMessage.findAll(xmlFile.readText()).forEach { m ->
                        val reason = m.groupValues[1]
                            .replace("&apos;", "'")
                            .replace("&quot;", "\"")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
                        logger.lifecycle("[guardrails] SKIPPED — $reason")
                    }
                }
        }
    }
}

val gitleaksScan = tasks.register("gitleaksScan") {
    group = "verification"
    description = "gitleaks 시크릿 스캔. PATH에 없으면 경고만 내고 넘어간다"
    doLast {
        val available = try {
            ProcessBuilder("gitleaks", "version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!available) {
            logger.warn("[guardrails] gitleaks가 PATH에 없어 로컬 스캔을 생략합니다. CI에서는 필수 단계입니다.")
            return@doLast
        }
        val exit = ProcessBuilder("gitleaks", "detect", "--no-banner", "--redact")
            .directory(rootDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exit != 0) {
            throw GradleException("gitleaks가 시크릿을 탐지했습니다 (exit=$exit)")
        }
    }
}

tasks.register("updateMigrationChecksums") {
    group = "verification"
    description = "새 마이그레이션을 추가한 뒤 해시 기록을 갱신한다 (append-only)"
    // forward-only 가드는 이 태스크가 append-only일 때만 가드다.
    // 예전 구현은 모든 항목을 통째로 다시 썼기 때문에,
    // "적용된 V1을 고친다 → 가드 실패 → 실패 메시지가 지목한 명령을 실행한다 → 통과"가 됐다.
    // 가드를 우회하는 방법을 가드의 실패 메시지가 알려주고 있었다.
    // 이제는 이미 기록된 파일의 해시가 달라지거나 사라지면 여기서 먼저 깨진다.
    doLast {
        val migrationDir = file("src/main/resources/db/migration")
        val target = file("docs/harness/migration-checksums.txt")
        val digest = MessageDigest.getInstance("SHA-256")

        fun hashOf(f: File): String {
            digest.reset()
            val normalized = f.readText().replace("\r\n", "\n")
            return digest.digest(normalized.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        val recorded = linkedMapOf<String, String>()
        if (target.isFile) {
            target.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) recorded[parts[0]] = parts[1]
                }
            }
        }

        val current = linkedMapOf<String, String>()
        if (migrationDir.isDirectory) {
            migrationDir.listFiles { f -> f.name.endsWith(".sql") }
                ?.sortedBy { it.name }
                ?.forEach { f -> current[f.name] = hashOf(f) }
        }

        recorded.forEach { (name, hash) ->
            val now = current[name]
                ?: throw GradleException(
                    "[guardrails] 이미 기록된 마이그레이션 '$name' 이(가) 사라졌습니다. " +
                        "Flyway는 forward-only입니다. 적용된 마이그레이션은 지우지 말고 " +
                        "되돌리는 새 버전 파일을 추가하세요. " +
                        "해시 기록은 append-only이므로 이 태스크로는 삭제를 반영할 수 없습니다."
                )
            if (now != hash) {
                throw GradleException(
                    "[guardrails] 이미 기록된 마이그레이션 '$name' 의 내용이 바뀌었습니다.\n" +
                        "  기록된 해시: $hash\n" +
                        "  현재 해시:   $now\n" +
                        "Flyway는 forward-only입니다. 적용된 마이그레이션을 고치지 말고 " +
                        "새 버전 파일(V<다음번호>__*.sql)을 추가하세요. " +
                        "'$name' 을 원래대로 되돌린 뒤 다시 실행하면 새 파일만 추가됩니다."
                )
            }
        }

        val added = current.keys - recorded.keys
        val lines = mutableListOf(
            "# Flyway 마이그레이션 해시. forward-only 강제용.",
            "# 새 파일을 추가했을 때만 ./gradlew updateMigrationChecksums 로 갱신한다.",
            "# 이 태스크는 append-only다 — 이미 기록된 파일이 바뀌면 갱신하지 않고 실패한다."
        )
        recorded.forEach { (name, hash) -> lines += "$name $hash" }
        added.sorted().forEach { name -> lines += "$name ${current[name]}" }

        target.parentFile.mkdirs()
        target.writeText(lines.joinToString("\n") + "\n")
        if (added.isEmpty()) {
            logger.lifecycle("[guardrails] 새 마이그레이션이 없습니다. ${target.path} 그대로입니다")
        } else {
            logger.lifecycle("[guardrails] ${target.path} 에 ${added.size}건 추가: ${added.sorted()}")
        }
    }
}

// ---------- 집합 게이트 ----------
// CI의 같은 이름 잡과 정확히 같은 것을 실행해야 한다

// 바닥 검사를 게이트의 직접 의존으로 건다. finalizedBy만으로도 걸리지만,
// 집합 게이트가 무엇에 기대고 있는지 빌드 파일에서 바로 보이게 한다.
val testNotEmpty = withZeroTestFloor(unitTest)
val integrationTestNotEmpty = withZeroTestFloor(integrationTest)
val guardrailTestNotEmpty = withZeroTestFloor(guardrailTest)

tasks.register("verify") {
    group = "verification"
    description = "기계 게이트 — compile + L1 + ArchUnit + L2 + 활성 불변식"
    dependsOn(unitTest, integrationTest, testNotEmpty, integrationTestNotEmpty)
}

tasks.register("guardrails") {
    group = "verification"
    description = "가드레일 게이트 — 문서 상한, ddl-auto, 마이그레이션 해시, log.md, gitleaks"
    dependsOn(guardrailTest, guardrailTestNotEmpty, gitleaksScan)
}
