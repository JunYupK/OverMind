package com.overmind.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** L2. Flyway 마이그레이션이 실제로 적용된 스키마 위에서 테스트가 돈다는 것을 확인한다. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FlywayMigrationTest extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void pgvector_extension_is_created_by_migration() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("V1 마이그레이션이 vector 확장을 생성해야 합니다")
                    .isEqualTo(1);
        }
    }
}
