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

    @ArchTest
    static final ArchRule AR_3_llm_sdk_stays_in_outbound_adapter =
            noClasses()
                    .that().resideOutsideOfPackage("com.overmind.adapter.out..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.anthropic..",
                            "com.openai..",
                            "dev.langchain4j..",
                            "io.modelcontextprotocol..")
                    .because("AR-3: LLM/MCP SDK는 진출 어댑터 안에 가둔다");

    @ArchTest
    static final ArchRule INV_02_domain_has_no_toString =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.overmind.domain..")
                    .should().haveName("toString")
                    .because("INV-02: 도메인 엔티티의 toString이 민감 값을 로그로 흘린다");
}
