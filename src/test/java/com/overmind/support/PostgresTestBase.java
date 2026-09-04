package com.overmind.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * L2 테스트의 공통 베이스.
 *
 * <p>컨테이너를 정적 싱글턴으로 한 번만 띄운다. JUnit의 {@code @Container}를 쓰면
 * 클래스마다 새로 뜨기 때문에 L2 전체 시간이 선형으로 늘어난다.
 *
 * <p>기본 postgres 이미지에는 vector 확장이 없으므로 pgvector 이미지를 쓴다.
 * 로컬에서 재사용을 켜려면 {@code ~/.testcontainers.properties}에
 * {@code testcontainers.reuse.enable=true}를 넣는다. CI에서는 자동으로 무시된다.
 */
public abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg16")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("overmind")
                    .withUsername("overmind")
                    .withPassword("overmind")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "overmind.security.cursor-secret", () -> "overmind-test-cursor-key-".repeat(2));
    }
}
