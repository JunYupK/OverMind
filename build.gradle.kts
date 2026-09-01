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

// ---------- 테스트 계층 ----------
// L1 = 태그 없음 / L2 = integration / L3 = evaluation / 가드레일 = guardrail

tasks.named<Test>("test") {
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
    testLogging { events("failed") }
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
    description = "새 마이그레이션을 추가한 뒤 해시 기록을 갱신한다"
    doLast {
        val migrationDir = file("src/main/resources/db/migration")
        val target = file("docs/harness/migration-checksums.txt")
        val digest = MessageDigest.getInstance("SHA-256")

        val lines = mutableListOf(
            "# Flyway 마이그레이션 해시. forward-only 강제용.",
            "# 새 파일을 추가했을 때만 ./gradlew updateMigrationChecksums 로 갱신한다.",
            "# 기존 파일을 고치고 갱신하는 것은 가드레일 우회다."
        )
        if (migrationDir.isDirectory) {
            migrationDir.listFiles { f -> f.name.endsWith(".sql") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    digest.reset()
                    val normalized = f.readText().replace("\r\n", "\n")
                    val hex = digest.digest(normalized.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    lines += "${f.name} $hex"
                }
        }
        target.parentFile.mkdirs()
        target.writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("[guardrails] ${target.path} 갱신 완료")
    }
}

// ---------- 집합 게이트 ----------
// CI의 같은 이름 잡과 정확히 같은 것을 실행해야 한다

tasks.register("verify") {
    group = "verification"
    description = "기계 게이트 — compile + L1 + ArchUnit + L2 + 활성 불변식"
    dependsOn(tasks.named("test"), integrationTest)
}

tasks.register("guardrails") {
    group = "verification"
    description = "가드레일 게이트 — 문서 상한, ddl-auto, 마이그레이션 해시, log.md, gitleaks"
    dependsOn(guardrailTest, gitleaksScan)
}
