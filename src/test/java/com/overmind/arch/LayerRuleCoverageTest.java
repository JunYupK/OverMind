package com.overmind.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * L1. AR-3 계열 규칙이 <b>공허하게</b> 통과하고 있지 않은지 본다.
 *
 * <p><b>왜 따로 있나.</b> ArchUnit 규칙은 위반이 없으면 조용히 통과한다. 그런데 AR-3이
 * 지키는 SDK 패키지는 Task 10에서 의존성이 들어오기 전까지 classpath에 아예 없다.
 * 즉 지금 세 규칙은 <b>검사할 대상이 없어서</b> 통과하는 중이고, 그 상태는 규칙을 통째로
 * 지운 것과 로그에서 구별되지 않는다. 이 저장소가 반복해서 당한 형태다.
 *
 * <p>그래서 둘을 본다 — 규칙의 <b>형태</b>가 실제로 위반을 잡는지, 그리고 규칙이 지키는
 * <b>패키지 목록</b>이 조용히 좁아지지 않았는지.
 *
 * <p>SDK를 실제로 코어에 끌어들여 보는 프로브는 Task 10에서 한다. 지금은 그 타입이 없어
 * 프로브 자체가 컴파일되지 않는다. 플랜 Task 10 Step 5가 그것을 들고 있다.
 *
 * <p>{@code @AnalyzeClasses}를 붙이지 않는다. 그러면 이 클래스는 평범한 JUnit 테스트로
 * 돌고, ArchUnit 엔진이 {@code @Test} 메서드를 어떻게 다루는지에 기대지 않는다.
 */
class LayerRuleCoverageTest {

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.overmind");
    }

    /**
     * 규칙의 형태가 실물이라는 것을 확인한다.
     *
     * <p>{@code adapter.out.persistence}는 실제로 {@code jakarta.persistence}를 쓴다.
     * AR-3과 같은 모양으로 그것을 금지하는 규칙을 만들면 <b>반드시 실패해야 한다.</b>
     * 통과한다면 이 모양의 규칙이 아무것도 안 보고 있다는 뜻이고, AR-3도 같이 못 믿는다.
     */
    @Test
    void a_rule_of_this_shape_actually_detects_a_violation() {
        ArchRule mustFail =
                noClasses()
                        .that().resideInAPackage("com.overmind.adapter.out..")
                        .should().dependOnClassesThat()
                        .resideInAnyPackage("jakarta.persistence..");

        assertThatThrownBy(() -> mustFail.check(productionClasses()))
                .as("영속화 어댑터는 jakarta.persistence를 쓴다. 이 규칙이 통과하면 검사가 죽은 것이다")
                .isInstanceOf(AssertionError.class);
    }

    /** 같은 모양이 위반 없는 대상에는 통과하는지 — 위 검사가 항상 실패하는 것은 아님을 못 박는다. */
    @Test
    void a_rule_of_this_shape_passes_when_there_is_no_violation() {
        ArchRule mustPass =
                noClasses()
                        .that().resideInAPackage("com.overmind.domain..")
                        .should().dependOnClassesThat()
                        .resideInAnyPackage("jakarta.persistence..");

        mustPass.check(productionClasses());
    }

    /**
     * AR-3을 방향별로 나누면서 지키는 대상이 줄지 않았는지 본다.
     *
     * <p>규칙 하나에서 패키지를 지우면 그 규칙은 여전히 초록이다. 목록을 여기서 못 박아
     * 조용한 축소를 실패로 만든다.
     */
    @Test
    void the_split_rules_still_guard_every_package_the_original_did() {
        assertThat(LayerDependencyTest.AR_3_llm_sdk_stays_in_outbound_adapter.getDescription())
                .as("LLM SDK 세 곳은 진출 어댑터에 계속 갇혀 있어야 한다")
                .contains("com.anthropic..", "com.openai..", "dev.langchain4j..");

        assertThat(LayerDependencyTest.AR_3A_mcp_sdk_stays_in_adapters.getDescription())
                .as("MCP SDK는 어댑터 밖에서 금지된 채로 남아야 한다")
                .contains("io.modelcontextprotocol..");

        assertThat(LayerDependencyTest.AR_3B_sdk_never_reaches_core.getDescription())
                .as("코어 격리는 MCP SDK와 Spring AI 재노출을 모두 막아야 한다")
                .contains("io.modelcontextprotocol..", "org.springframework.ai..");
    }

    /**
     * 나눈 뒤에도 코어 격리 규칙이 도메인과 application을 <b>둘 다</b> 겨눈다는 것.
     *
     * <p>한쪽만 남겨도 규칙은 초록이므로 설명 문자열로 범위를 고정한다.
     */
    @Test
    void the_core_isolation_rule_covers_both_core_packages() {
        assertThat(LayerDependencyTest.AR_3B_sdk_never_reaches_core.getDescription())
                .contains("com.overmind.domain..", "com.overmind.application..");
    }
}
