package com.overmind.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** L1. 아키텍처 의존 방향과 로깅 관련 불변식을 강제한다. */
@AnalyzeClasses(packages = "com.overmind", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    @ArchTest
    static final ArchRule AR_1_domain_is_pure =
            noClasses()
                    .that().resideInAPackage("com.overmind.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.overmind.adapter..")
                    .because("AR-1: domain은 순수해야 한다. 프레임워크와 어댑터에 의존하지 않는다");

    @ArchTest
    static final ArchRule AR_2_application_does_not_depend_on_adapter =
            noClasses()
                    .that().resideInAPackage("com.overmind.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.overmind.adapter..")
                    .because("AR-2: application은 port 인터페이스로만 바깥과 통신한다");

    /**
     * LLM SDK는 <b>진출</b> 어댑터에만 둔다. 우리가 바깥을 부르는 방향이다.
     *
     * <p>원래 이 규칙은 MCP SDK도 함께 막았다. 그런데 MCP 서버는 바깥이 우리를 부르는
     * <b>진입</b> 어댑터이므로 {@code adapter.in}에 산다 (스펙 §3). 한 규칙으로 둘을 묶으면
     * 스펙대로 구현하는 순간 게이트가 실패한다. 규칙을 약화시키는 대신 방향별로 나눴다 —
     * 격리 대상은 그대로다.
     */
    @ArchTest
    static final ArchRule AR_3_llm_sdk_stays_in_outbound_adapter =
            noClasses()
                    .that().resideOutsideOfPackage("com.overmind.adapter.out..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.anthropic..", "com.openai..", "dev.langchain4j..")
                    .because("AR-3: LLM SDK는 진출 어댑터 안에 가둔다");

    /**
     * MCP SDK는 어댑터 안이면 진입·진출 어디든 좋다. 서버는 진입이고 클라이언트는 진출이다.
     *
     * <p>{@code config}는 {@code adapter..} 밖이므로 여기에도 걸린다 — 빈을 등록할 때
     * SDK 타입을 직접 참조하면 실패한다. 의도된 것이다.
     */
    @ArchTest
    static final ArchRule AR_3A_mcp_sdk_stays_in_adapters =
            noClasses()
                    .that().resideOutsideOfPackage("com.overmind.adapter..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.modelcontextprotocol..")
                    .because("AR-3: MCP SDK는 어댑터 안에 가둔다. MCP 서버는 진입 어댑터다");

    /**
     * 나누면서 잃지 않았다는 것을 못 박는 절반. <b>코어는 여전히 SDK를 모른다.</b>
     *
     * <p>{@link #AR_3A_mcp_sdk_stays_in_adapters}는 "어댑터 안이면 된다"고만 말하므로,
     * 그것만으로는 규칙이 넓어졌을 때 눈에 띄지 않는다. 이 규칙이 도메인·application 쪽을
     * 따로 잠근다. Spring AI가 MCP 타입을 자기 패키지로 재노출하므로 그쪽도 함께 막는다.
     */
    @ArchTest
    static final ArchRule AR_3B_sdk_never_reaches_core =
            noClasses()
                    .that()
                    .resideInAnyPackage("com.overmind.domain..", "com.overmind.application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("io.modelcontextprotocol..", "org.springframework.ai..")
                    .because("AR-3: MCP/AI SDK는 어댑터 안에 가둔다. 코어는 이 타입들을 몰라야 한다");

    @ArchTest
    static final ArchRule INV_02_domain_has_no_toString =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.overmind.domain..")
                    .should().haveName("toString")
                    .because("INV-02: 도메인 엔티티의 toString이 민감 값을 로그로 흘린다");
}
