// JDK 21이 없는 기계에서 Gradle이 툴체인을 스스로 내려받게 한다.
// 이 저장소는 Java 21 툴체인을 요구하는데, 그 사실이 어디에도 적혀 있지 않아
// 새 세션이 stale JAVA_HOME으로 빌드에 실패했다. 문서(20-build-and-test.md)와
// 이 리졸버 둘 다 둔다 — 문서는 사람을, 리졸버는 기계를 구한다.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "overmind"
