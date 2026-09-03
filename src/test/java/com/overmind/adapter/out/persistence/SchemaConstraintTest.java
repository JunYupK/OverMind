package com.overmind.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.support.PostgresTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** L2. M0 persistence schema constraints are enforced by PostgreSQL. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchemaConstraintTest extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void subject_type_and_key_invariants_are_enforced() throws Exception {
        inTransaction(
                connection -> {
                    insertSubject(connection, UUID.randomUUID(), "USER", null);
                    assertConstraint(
                            connection,
                            "23505",
                            "memory_subject_single_user",
                            () -> insertSubject(connection, UUID.randomUUID(), "USER", null));
                    assertConstraint(
                            connection,
                            "23514",
                            "memory_subject_key_matches_type",
                            () ->
                                    insertSubject(
                                            connection, UUID.randomUUID(), "USER", "not-allowed"));
                    assertConstraint(
                            connection,
                            "23514",
                            "memory_subject_key_matches_type",
                            () ->
                                    insertSubject(
                                            connection, UUID.randomUUID(), "UNKNOWN", "unknown"));
                    assertConstraint(
                            connection,
                            "23514",
                            "memory_subject_key_matches_type",
                            () -> insertSubject(connection, UUID.randomUUID(), "PROJECT", null));
                    assertConstraint(
                            connection,
                            "23514",
                            "memory_subject_project_key_form",
                            () ->
                                    insertSubject(
                                            connection, UUID.randomUUID(), "PROJECT", "Uppercase"));
                    assertConstraint(
                            connection,
                            "23514",
                            "memory_subject_project_key_form",
                            () ->
                                    insertSubject(
                                            connection,
                                            UUID.randomUUID(),
                                            "PROJECT",
                                            "a".repeat(129)));
                });
    }

    @Test
    void project_keys_are_unique_per_subject_type() throws Exception {
        inTransaction(
                connection -> {
                    insertSubject(connection, UUID.randomUUID(), "PROJECT", "shared-project");
                    assertConstraint(
                            connection,
                            "23505",
                            "memory_subject_type_key_unique",
                            () ->
                                    insertSubject(
                                            connection,
                                            UUID.randomUUID(),
                                            "PROJECT",
                                            "shared-project"));
                });
    }

    @Test
    void required_columns_reject_nulls_before_checks_can_be_bypassed() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    insertSubject(connection, subjectId, "PROJECT", "nulls-project");
                    assertNotNull(
                            connection,
                            "type",
                            () -> insertSubject(connection, UUID.randomUUID(), null, null));
                    assertNotNull(
                            connection,
                            "idempotency_key",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            null,
                                            "content",
                                            "client",
                                            "conversation",
                                            "message"));
                    assertNotNull(
                            connection,
                            "content",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            "null-content",
                                            null,
                                            "client",
                                            "conversation",
                                            "message"));
                    assertNotNull(
                            connection,
                            "source_client",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            "null-client",
                                            "content",
                                            null,
                                            "conversation",
                                            "message"));
                });
    }

    @Test
    void observation_rejects_unknown_subject_without_persisting_a_row() throws Exception {
        inTransaction(
                connection -> {
                    UUID unknownSubject = UUID.randomUUID();
                    assertConstraint(
                            connection,
                            "23503",
                            "observation_subject_fk",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            unknownSubject,
                                            "missing-subject",
                                            "content",
                                            "client",
                                            "conversation",
                                            "message"));
                    assertThat(count(connection, "SELECT count(*) FROM observation")).isZero();
                });
    }

    @Test
    void subject_delete_is_rejected_and_preserves_its_observation() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    insertSubject(connection, subjectId, "PROJECT", "non-cascading-subject");
                    insertObservation(
                            connection,
                            UUID.randomUUID(),
                            subjectId,
                            "non-cascading-observation",
                            "content",
                            "client",
                            "conversation",
                            "message");

                    assertConstraint(
                            connection,
                            "23503",
                            "observation_subject_fk",
                            () -> deleteSubject(connection, subjectId));
                    assertThat(count(connection, "SELECT count(*) FROM memory_subject")).isEqualTo(1);
                    assertThat(count(connection, "SELECT count(*) FROM observation")).isEqualTo(1);
                });
    }

    @Test
    void idempotency_keys_are_globally_unique_across_subjects() throws Exception {
        inTransaction(
                connection -> {
                    UUID firstSubject = UUID.randomUUID();
                    UUID secondSubject = UUID.randomUUID();
                    insertSubject(connection, firstSubject, "PROJECT", "first-project");
                    insertSubject(connection, secondSubject, "PROJECT", "second-project");
                    insertObservation(
                            connection,
                            UUID.randomUUID(),
                            firstSubject,
                            "global-idempotency-key",
                            "first",
                            "client",
                            "conversation",
                            "message");

                    assertConstraint(
                            connection,
                            "23505",
                            "observation_idempotency_key_unique",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            secondSubject,
                                            "global-idempotency-key",
                                            "second",
                                            "client",
                                            "conversation",
                                            "message"));
                });
    }

    @Test
    void required_text_fields_use_java_is_blank_whitespace_semantics() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    insertSubject(connection, subjectId, "PROJECT", "whitespace-project");
                    for (String blank : List.of("\t", "\n", "\u2003")) {
                        assertConstraint(
                                connection,
                                "23514",
                                "observation_content_not_blank",
                                () ->
                                        insertObservation(
                                                connection,
                                                UUID.randomUUID(),
                                                subjectId,
                                                "content-" + UUID.randomUUID(),
                                                blank,
                                                "client",
                                                "conversation",
                                                "message"));
                        assertConstraint(
                                connection,
                                "23514",
                                "observation_idempotency_key_not_blank",
                                () ->
                                        insertObservation(
                                                connection,
                                                UUID.randomUUID(),
                                                subjectId,
                                                blank,
                                                "content",
                                                "client",
                                                "conversation",
                                                "message"));
                        assertSourceBlankRejected(connection, subjectId, blank, "client");
                        assertSourceBlankRejected(connection, subjectId, blank, "conversation");
                        assertSourceBlankRejected(connection, subjectId, blank, "message");
                    }
                });
    }

    @Test
    void nonblank_text_with_padding_is_preserved() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    String content = " \tkept\u2003 ";
                    insertSubject(connection, subjectId, "PROJECT", "padded-project");
                    insertObservation(
                            connection,
                            UUID.randomUUID(),
                            subjectId,
                            " padded-idempotency ",
                            content,
                            " client ",
                            " conversation ",
                            " message ");
                    assertThat(queryText(connection, "SELECT content FROM observation")).isEqualTo(content);
                });
    }

    @Test
    void nonbreaking_space_is_not_treated_as_java_whitespace() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    insertSubject(connection, subjectId, "PROJECT", "nbsp-project");
                    insertObservation(
                            connection,
                            UUID.randomUUID(),
                            subjectId,
                            "\u00a0",
                            "\u00a0",
                            "\u00a0",
                            "\u00a0",
                            "\u00a0");
                    assertThat(count(connection, "SELECT count(*) FROM observation")).isEqualTo(1);
                });
    }

    @Test
    void text_byte_limits_accept_the_boundary_and_reject_one_byte_over() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    insertSubject(connection, subjectId, "PROJECT", "byte-boundary-project");
                    insertObservation(
                            connection,
                            UUID.randomUUID(),
                            subjectId,
                            "é".repeat(128),
                            "한".repeat(5461) + "a",
                            "é".repeat(64),
                            "é".repeat(256),
                            "é".repeat(256));

                    assertConstraint(
                            connection,
                            "23514",
                            "observation_idempotency_key_size",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            "é".repeat(128) + "a",
                                            "content",
                                            "client",
                                            "conversation",
                                            "message"));
                    assertConstraint(
                            connection,
                            "23514",
                            "observation_content_size",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            "content-over-limit",
                                            "한".repeat(5461) + "aa",
                                            "client",
                                            "conversation",
                                            "message"));
                    assertSourceSizeRejected(connection, subjectId, "client", "é".repeat(64) + "a");
                    assertSourceSizeRejected(
                            connection, subjectId, "conversation", "é".repeat(256) + "a");
                    assertSourceSizeRejected(connection, subjectId, "message", "é".repeat(256) + "a");
                });
    }

    @Test
    void ingestion_type_and_input_schema_version_are_constrained() throws Exception {
        inTransaction(
                connection -> {
                    UUID subjectId = UUID.randomUUID();
                    insertSubject(connection, subjectId, "PROJECT", "ingestion-project");
                    assertConstraint(
                            connection,
                            "23514",
                            "observation_ingestion_type_known",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            "unknown-ingestion",
                                            "content",
                                            "client",
                                            "conversation",
                                            "message",
                                            "UNKNOWN",
                                            1));
                    assertConstraint(
                            connection,
                            "23514",
                            "observation_input_schema_version_positive",
                            () ->
                                    insertObservation(
                                            connection,
                                            UUID.randomUUID(),
                                            subjectId,
                                            "zero-schema-version",
                                            "content",
                                            "client",
                                            "conversation",
                                            "message",
                                            "DIRECT_MCP",
                                            0));
                });
    }

    @Test
    void recall_index_has_the_specified_key_order_and_directions() throws Exception {
        inTransaction(
                connection ->
                        assertThat(
                                        queryText(
                                                connection,
                                                "SELECT pg_get_indexdef(indexrelid) FROM pg_index "
                                                        + "WHERE indexrelid = 'observation_recall_keyset'::regclass"))
                                .contains(
                                        "(subject_id, observed_at DESC, created_at DESC, id DESC)"));
    }

    private void assertSourceBlankRejected(
            Connection connection, UUID subjectId, String blank, String field) throws Exception {
        String expectedConstraint =
                switch (field) {
                    case "client" -> "observation_source_client_not_blank";
                    case "conversation" -> "observation_source_conversation_id_not_blank";
                    case "message" -> "observation_source_message_id_not_blank";
                    default -> throw new IllegalArgumentException("unsupported source field: " + field);
                };
        assertConstraint(
                connection,
                "23514",
                expectedConstraint,
                () ->
                        insertObservation(
                                connection,
                                UUID.randomUUID(),
                                subjectId,
                                "source-blank-" + field + UUID.randomUUID(),
                                "content",
                                field.equals("client") ? blank : "client",
                                field.equals("conversation") ? blank : "conversation",
                                field.equals("message") ? blank : "message"));
    }

    private void assertSourceSizeRejected(
            Connection connection, UUID subjectId, String field, String tooLong) throws Exception {
        String expectedConstraint =
                switch (field) {
                    case "client" -> "observation_source_client_size";
                    case "conversation" -> "observation_source_conversation_id_size";
                    case "message" -> "observation_source_message_id_size";
                    default -> throw new IllegalArgumentException("unsupported source field: " + field);
                };
        assertConstraint(
                connection,
                "23514",
                expectedConstraint,
                () ->
                        insertObservation(
                                connection,
                                UUID.randomUUID(),
                                subjectId,
                                "source-size-" + field,
                                "content",
                                field.equals("client") ? tooLong : "client",
                                field.equals("conversation") ? tooLong : "conversation",
                                field.equals("message") ? tooLong : "message"));
    }

    private void insertSubject(Connection connection, UUID id, String type, String subjectKey)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO memory_subject (id, type, subject_key, created_at) "
                                + "VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, type);
            statement.setString(3, subjectKey);
            statement.setObject(4, utc("2026-09-03T00:00:00Z"));
            statement.executeUpdate();
        }
    }

    private void deleteSubject(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM memory_subject WHERE id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private void insertObservation(
            Connection connection,
            UUID id,
            UUID subjectId,
            String idempotencyKey,
            String content,
            String sourceClient,
            String sourceConversationId,
            String sourceMessageId)
            throws SQLException {
        insertObservation(
                connection,
                id,
                subjectId,
                idempotencyKey,
                content,
                sourceClient,
                sourceConversationId,
                sourceMessageId,
                "DIRECT_MCP",
                1);
    }

    private void insertObservation(
            Connection connection,
            UUID id,
            UUID subjectId,
            String idempotencyKey,
            String content,
            String sourceClient,
            String sourceConversationId,
            String sourceMessageId,
            String ingestionType,
            int inputSchemaVersion)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO observation (id, subject_id, idempotency_key, content, observed_at, "
                                + "created_at, source_client, source_conversation_id, source_message_id, "
                                + "ingestion_type, input_schema_version) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, subjectId);
            statement.setString(3, idempotencyKey);
            statement.setString(4, content);
            statement.setObject(5, utc("2026-09-03T00:00:00Z"));
            statement.setObject(6, utc("2026-09-03T00:00:01Z"));
            statement.setString(7, sourceClient);
            statement.setString(8, sourceConversationId);
            statement.setString(9, sourceMessageId);
            statement.setString(10, ingestionType);
            statement.setInt(11, inputSchemaVersion);
            statement.executeUpdate();
        }
    }

    private void assertConstraint(
            Connection connection, String sqlState, String constraint, SqlWork work) throws Exception {
        Savepoint savepoint = connection.setSavepoint();
        try {
            work.run();
            throw new AssertionError("expected constraint " + constraint + " to reject the operation");
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).isEqualTo(sqlState);
            assertThat(exception.getMessage()).contains("\"" + constraint + "\"");
        } finally {
            connection.rollback(savepoint);
        }
    }

    private void assertNotNull(Connection connection, String column, SqlWork work) throws Exception {
        Savepoint savepoint = connection.setSavepoint();
        try {
            work.run();
            throw new AssertionError("expected column " + column + " to reject null");
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).isEqualTo("23502");
            assertThat(exception.getMessage()).contains("column \"" + column + "\"");
        } finally {
            connection.rollback(savepoint);
        }
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryText(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    private OffsetDateTime utc(String instant) {
        return OffsetDateTime.parse(instant).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private void inTransaction(SqlTransaction work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface SqlTransaction {
        void run(Connection connection) throws Exception;
    }
}
