package com.overmind.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.OvermindApplication;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.IngestionType;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ObservationContent;
import com.overmind.domain.memory.ProjectKey;
import com.overmind.domain.memory.SourceReference;
import com.overmind.domain.memory.SubjectType;
import com.overmind.support.PostgresTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** L2. PostgreSQL insert-or-find paths converge without poisoning their transaction. */
@Tag("integration")
@SpringBootTest(
        classes = {OvermindApplication.class, InsertOrFindConcurrencyTest.FixedClockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InsertOrFindConcurrencyTest extends PostgresTestBase {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final String TEST_PREFIX = "t4-" + UUID.randomUUID().toString().replace("-", "");
    private static final String TEST_SCHEMA = "t4_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private SubjectRepository subjects;
    @Autowired private ObservationRepository observations;
    @Autowired private TransactionBoundary transactions;
    @Autowired private DataSource dataSource;

    private final Set<UUID> subjectIds = ConcurrentHashMap.newKeySet();
    private final Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();

    @DynamicPropertySource
    static void t4SchemaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> TEST_SCHEMA);
        registry.add("spring.flyway.schemas", () -> TEST_SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> TEST_SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> TEST_SCHEMA);
    }

    @AfterEach
    void deleteOnlyRowsOwnedByThisTest() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTestSchema(connection);
            connection.setAutoCommit(false);
            try {
                deleteObservations(connection);
                deleteSubjects(connection);
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void concurrent_project_creation_converges_on_one_subject_and_one_row() throws Exception {
        ProjectKey key = ProjectKey.of(testValue("project-race"));

        List<MemorySubject> created = runConcurrently(8, () -> subjects.findOrCreateProject(key));
        created.forEach(subject -> subjectIds.add(subject.id()));

        assertThat(created).extracting(MemorySubject::id).containsOnly(created.getFirst().id());
        assertThat(countSubjects("PROJECT", key.value())).isEqualTo(1);
    }

    @Test
    void concurrent_user_creation_converges_on_one_subject_and_one_row() throws Exception {
        List<MemorySubject> created = runConcurrently(8, subjects::findOrCreateUser);
        created.forEach(subject -> subjectIds.add(subject.id()));

        assertThat(created).extracting(MemorySubject::id).containsOnly(created.getFirst().id());
        assertThat(countSubjects("USER", null)).isEqualTo(1);
    }

    @Test
    void concurrent_identical_inserts_return_and_store_exactly_one_winner() throws Exception {
        MemorySubject subject = track(subjects.findOrCreateUser());
        IdempotencyKey key = track(IdempotencyKey.of(testValue("idempotency-race")));

        List<Observation> results =
                runConcurrently(
                        8,
                        () ->
                                observations.insertIfAbsent(
                                        observation(
                                                UUID.randomUUID(),
                                                subject,
                                                key,
                                                "same content",
                                                "race")));

        assertThat(results).extracting(Observation::id).containsOnly(results.getFirst().id());
        assertThat(countObservations(key.value())).isEqualTo(1);
        assertThat(observations.findByIdempotencyKey(key)).hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(results.getFirst().id()));
    }

    @Test
    void duplicate_with_different_fields_returns_the_stored_winner() throws Exception {
        MemorySubject firstSubject = track(subjects.findOrCreateUser());
        MemorySubject secondSubject =
                track(subjects.findOrCreateProject(ProjectKey.of(testValue("winner-project"))));
        IdempotencyKey key = track(IdempotencyKey.of(testValue("winner-key")));
        Observation stored = observation(UUID.randomUUID(), firstSubject, key, "stored content", "stored");
        Observation competing = observation(UUID.randomUUID(), secondSubject, key, "different content", "competing");

        assertThat(observations.insertIfAbsent(stored)).isEqualTo(stored);
        Observation winner = observations.insertIfAbsent(competing);

        assertThat(winner.id()).isEqualTo(stored.id());
        assertThat(winner.subject()).isEqualTo(firstSubject);
        assertThat(winner.content().value()).isEqualTo("stored content");
        assertThat(countObservations(key.value())).isEqualTo(1);
    }

    @Test
    void missing_project_lookup_does_not_create_a_subject() throws Exception {
        ProjectKey missing = ProjectKey.of(testValue("missing"));

        assertThat(subjects.findProject(missing)).isEmpty();
        assertThat(countSubjects("PROJECT", missing.value())).isZero();
    }

    @Test
    void find_by_idempotency_key_rehydrates_every_project_observation_field() {
        ProjectKey projectKey = ProjectKey.of(testValue("rehydrate-project"));
        MemorySubject subject = track(subjects.findOrCreateProject(projectKey));
        IdempotencyKey key = track(IdempotencyKey.of(testValue("rehydrate-key")));
        Instant observedAt = Instant.parse("2026-09-02T10:11:12.123456Z");
        Instant createdAt = Instant.parse("2026-09-02T10:12:13.654321Z");
        SourceReference source = SourceReference.of(" client ", " conversation ", " message ");
        Observation expected =
                Observation.create(
                        UUID.randomUUID(),
                        subject,
                        key,
                        ObservationContent.of(" exact content "),
                        observedAt,
                        createdAt,
                        source,
                        IngestionType.DIRECT_MCP,
                        1);

        observations.insertIfAbsent(expected);
        Observation actual = observations.findByIdempotencyKey(key).orElseThrow();

        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.subject()).isEqualTo(subject);
        assertThat(actual.subject().type()).isEqualTo(SubjectType.PROJECT);
        assertThat(actual.subject().projectKey()).contains(projectKey);
        assertThat(actual.idempotencyKey()).isEqualTo(key);
        assertThat(actual.content()).isEqualTo(expected.content());
        assertThat(actual.observedAt()).isEqualTo(observedAt);
        assertThat(actual.createdAt()).isEqualTo(createdAt);
        assertThat(actual.source()).isEqualTo(source);
        assertThat(actual.ingestionType()).isEqualTo(IngestionType.DIRECT_MCP);
        assertThat(actual.inputSchemaVersion()).isEqualTo(1);
    }

    @Test
    void find_by_idempotency_key_rehydrates_a_user_subject() {
        MemorySubject subject = track(subjects.findOrCreateUser());
        IdempotencyKey key = track(IdempotencyKey.of(testValue("rehydrate-user")));
        Observation expected = observation(UUID.randomUUID(), subject, key, "user content", "user");

        observations.insertIfAbsent(expected);
        Observation actual = observations.findByIdempotencyKey(key).orElseThrow();

        assertThat(actual.subject()).isEqualTo(subject);
        assertThat(actual.subject().type()).isEqualTo(SubjectType.USER);
        assertThat(actual.subject().projectKey()).isEmpty();
    }

    @Test
    void subject_created_at_uses_the_injected_clock() throws Exception {
        MemorySubject project = track(subjects.findOrCreateProject(ProjectKey.of(testValue("clock"))));

        assertThat(createdAt(project.id())).isEqualTo(FIXED_NOW);
    }

    @Test
    void transaction_boundary_rolls_back_a_new_project_and_its_observation() throws Exception {
        ProjectKey key = ProjectKey.of(testValue("rolled-back-project"));
        IdempotencyKey idempotencyKey = track(IdempotencyKey.of(testValue("rolled-back-observation")));

        assertThatThrownBy(
                        () ->
                                transactions.inTransaction(
                                        () -> {
                                            MemorySubject subject = track(subjects.findOrCreateProject(key));
                                            observations.insertIfAbsent(
                                                    observation(
                                                            UUID.randomUUID(),
                                                            subject,
                                                            idempotencyKey,
                                                            "rolled back",
                                                            "rollback"));
                                            throw new IllegalStateException("force rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(subjects.findProject(key)).isEmpty();
        assertThat(countObservations(idempotencyKey.value())).isZero();
    }

    @Test
    void duplicate_insert_keeps_outer_transaction_usable_for_a_later_write() throws Exception {
        MemorySubject user = track(subjects.findOrCreateUser());
        IdempotencyKey duplicateKey = track(IdempotencyKey.of(testValue("outer-duplicate")));
        ProjectKey laterProjectKey = ProjectKey.of(testValue("later-project"));
        IdempotencyKey laterObservationKey = track(IdempotencyKey.of(testValue("later-observation")));

        transactions.inTransaction(
                () -> {
                    Observation stored = observation(UUID.randomUUID(), user, duplicateKey, "stored", "first");
                    observations.insertIfAbsent(stored);
                    Observation winner =
                            observations.insertIfAbsent(
                                    observation(
                                            UUID.randomUUID(),
                                            user,
                                            duplicateKey,
                                            "different",
                                            "second"));
                    assertThat(winner.id()).isEqualTo(stored.id());
                    MemorySubject laterProject = track(subjects.findOrCreateProject(laterProjectKey));
                    observations.insertIfAbsent(
                            observation(
                                    UUID.randomUUID(),
                                    laterProject,
                                    laterObservationKey,
                                    "later write",
                                    "later"));
                    return null;
                });

        assertThat(countObservations(duplicateKey.value())).isEqualTo(1);
        assertThat(subjects.findProject(laterProjectKey)).isPresent();
        assertThat(countObservations(laterObservationKey.value())).isEqualTo(1);
    }

    @Test
    void foreign_key_failure_propagates_instead_of_becoming_an_idempotency_success() {
        IdempotencyKey key = track(IdempotencyKey.of(testValue("missing-subject")));
        Observation invalid =
                observation(
                        UUID.randomUUID(),
                        MemorySubject.user(UUID.randomUUID()),
                        key,
                        "must fail",
                        "missing-subject");

        assertThatThrownBy(() -> observations.insertIfAbsent(invalid))
                .satisfies(failure -> assertConstraint(failure, "23503", "observation_subject_fk"));
    }

    @Test
    void primary_key_failure_propagates_instead_of_becoming_an_idempotency_success() {
        MemorySubject subject = track(subjects.findOrCreateUser());
        UUID observationId = UUID.randomUUID();
        IdempotencyKey firstKey = track(IdempotencyKey.of(testValue("primary-first")));
        IdempotencyKey secondKey = track(IdempotencyKey.of(testValue("primary-second")));

        observations.insertIfAbsent(observation(observationId, subject, firstKey, "first", "primary-first"));

        assertThatThrownBy(
                        () ->
                                observations.insertIfAbsent(
                                        observation(
                                                observationId,
                                                subject,
                                                secondKey,
                                                "second",
                                                "primary-second")))
                .satisfies(failure -> assertConstraint(failure, "23505", "observation_pkey"));
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures =
                    java.util.stream.IntStream.range(0, threads)
                            .mapToObj(
                                    ignored ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!gate.await(10, TimeUnit.SECONDS)) {
                                                            throw new IllegalStateException("workers did not start together");
                                                        }
                                                        return task.call();
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private MemorySubject track(MemorySubject subject) {
        subjectIds.add(subject.id());
        return subject;
    }

    private IdempotencyKey track(IdempotencyKey key) {
        idempotencyKeys.add(key.value());
        return key;
    }

    private Observation observation(
            UUID id,
            MemorySubject subject,
            IdempotencyKey key,
            String content,
            String suffix) {
        return Observation.create(
                id,
                subject,
                key,
                ObservationContent.of(content),
                Instant.parse("2026-09-02T10:00:00Z"),
                Instant.parse("2026-09-02T10:00:01Z"),
                SourceReference.of("client-" + suffix, "conversation-" + suffix, "message-" + suffix),
                IngestionType.DIRECT_MCP,
                1);
    }

    private String testValue(String suffix) {
        return TEST_PREFIX + "-" + suffix;
    }

    private long countSubjects(String type, String subjectKey) throws Exception {
        String sql =
                subjectKey == null
                        ? "SELECT count(*) FROM memory_subject WHERE type = ?"
                        : "SELECT count(*) FROM memory_subject WHERE type = ? AND subject_key = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            assertTestSchema(connection);
            statement.setString(1, type);
            if (subjectKey != null) {
                statement.setString(2, subjectKey);
            }
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
        }
    }

    private long countObservations(String idempotencyKey) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT count(*) FROM observation WHERE idempotency_key = ?")) {
            assertTestSchema(connection);
            statement.setString(1, idempotencyKey);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
        }
    }

    private Instant createdAt(UUID subjectId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT created_at FROM memory_subject WHERE id = ?")) {
            assertTestSchema(connection);
            statement.setObject(1, subjectId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getObject(1, java.time.OffsetDateTime.class).toInstant();
            }
        }
    }

    private void deleteObservations(Connection connection) throws Exception {
        if (idempotencyKeys.isEmpty()) {
            return;
        }
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM observation WHERE idempotency_key = ANY (?)")) {
            statement.setArray(1, connection.createArrayOf("text", idempotencyKeys.toArray(String[]::new)));
            statement.executeUpdate();
        }
    }

    private void deleteSubjects(Connection connection) throws Exception {
        if (subjectIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM memory_subject WHERE id = ANY (?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", subjectIds.toArray(UUID[]::new)));
            statement.executeUpdate();
        }
    }

    private static void assertConstraint(Throwable failure, String sqlState, String constraint) {
        SQLException sqlException = findSqlException(failure);
        assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
        assertThat(sqlException.getMessage()).contains(constraint);
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected a PostgreSQL constraint failure", failure);
    }

    private static void assertTestSchema(Connection connection) throws Exception {
        assertThat(connection.getSchema()).isEqualTo(TEST_SCHEMA);
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_schema()");
                ResultSet results = statement.executeQuery()) {
            results.next();
            assertThat(results.getString(1)).isEqualTo(TEST_SCHEMA);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
