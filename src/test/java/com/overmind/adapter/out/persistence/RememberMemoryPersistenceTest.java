package com.overmind.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.OvermindApplication;
import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.memory.RecallCursor;
import com.overmind.application.memory.RecallPage;
import com.overmind.application.memory.RememberCommand;
import com.overmind.application.memory.RememberMemory;
import com.overmind.application.memory.RememberResult;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ProjectKey;
import com.overmind.domain.memory.SubjectType;
import com.overmind.support.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real use-case transactions: durable retries, insert-race decisions and failure rollback. */
@Tag("integration")
@SpringBootTest(classes = OvermindApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RememberMemoryPersistenceTest extends PostgresTestBase {
    private static final String SCHEMA = "t5_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-09-02T12:00:00.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired private SubjectRepository subjects;
    @Autowired private ObservationRepository observations;
    @Autowired private TransactionBoundary transactions;
    @Autowired private JdbcTemplate jdbc;

    private final String prefix = "t5-" + UUID.randomUUID();

    @DynamicPropertySource
    static void schemaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
    }

    @BeforeEach
    void verifyDedicatedSchema() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @AfterEach
    void deleteOnlyOwnedRows() {
        jdbc.update("DELETE FROM observation WHERE idempotency_key LIKE ?", prefix + "%");
        jdbc.update("DELETE FROM memory_subject WHERE subject_key LIKE ?", prefix + "%");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-02T12:00:00.123456Z", "1969-12-31T23:59:59.999999Z"})
    void microsecond_timestamp_survives_commit_and_identical_retry(String timestamp) {
        Instant instant = Instant.parse(timestamp);
        RememberCommand command = command("retry", "project", " exact content ", instant);
        RememberMemory remember = useCase(observations);

        RememberResult first = remember.handle(command);
        Observation stored = observations.findByIdempotencyKey(IdempotencyKey.of(command.idempotencyKey())).orElseThrow();
        RememberResult retry = remember.handle(command);

        assertThat(first.created()).isTrue();
        assertThat(stored.id()).isEqualTo(first.observationId());
        assertThat(stored.observedAt()).isEqualTo(instant);
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(retry).isEqualTo(new RememberResult(first.observationId(), false));
        assertThat(observationCount(command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void finer_timestamp_is_rejected_without_creating_a_project_or_observation() {
        RememberCommand command = command("fine", "fine-project", "valid", OBSERVED.plusNanos(1));

        assertThatThrownBy(() -> useCase(observations).handle(command))
                .isInstanceOfSatisfying(MemoryException.class,
                        error -> assertThat(error.code()).isEqualTo(MemoryErrorCode.INVALID_ARGUMENT));

        assertThat(subjects.findProject(ProjectKey.of(command.projectKey()))).isEmpty();
        assertThat(observationCount(command.idempotencyKey())).isZero();
    }

    @Test
    void a_one_microsecond_difference_is_a_conflict_after_commit() {
        RememberCommand first = command("precision", "project", "same", OBSERVED);
        RememberCommand changed = command("precision", "project", "same", OBSERVED.plusNanos(1000));
        RememberMemory remember = useCase(observations);
        RememberResult stored = remember.handle(first);

        assertThatThrownBy(() -> remember.handle(changed))
                .isInstanceOfSatisfying(MemoryException.class,
                        error -> assertThat(error.code()).isEqualTo(MemoryErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(remember.handle(first)).isEqualTo(new RememberResult(stored.observationId(), false));
        assertThat(observationCount(first.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void identical_requests_that_both_miss_the_initial_lookup_converge_on_one_success() throws Exception {
        RememberCommand command = command("same-race", null, "same", OBSERVED);
        List<Outcome> outcomes = race(command, command);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.result().created()).containsExactlyInAnyOrder(true, false);
        assertThat(outcomes.getFirst().result().observationId()).isEqualTo(outcomes.getLast().result().observationId());
        assertThat(observationCount(command.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void different_projects_racing_on_one_key_leave_only_the_winner_and_its_observation() throws Exception {
        RememberCommand first = command("different-race", "first-project", "same", OBSERVED);
        RememberCommand second = command("different-race", "second-project", "same", OBSERVED);
        List<Outcome> outcomes = race(first, second);

        Outcome winner = outcomes.stream().filter(outcome -> outcome.error() == null).findFirst().orElseThrow();
        Outcome loser = outcomes.stream().filter(outcome -> outcome.error() != null).findFirst().orElseThrow();
        assertThat(winner.result().created()).isTrue();
        assertThat(loser.error()).isEqualTo(MemoryErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(subjects.findProject(ProjectKey.of(winner.command().projectKey()))).isPresent();
        assertThat(subjects.findProject(ProjectKey.of(loser.command().projectKey()))).isEmpty();
        Observation stored = observations.findByIdempotencyKey(IdempotencyKey.of(first.idempotencyKey())).orElseThrow();
        assertThat(stored.id()).isEqualTo(winner.result().observationId());
        assertThat(stored.subject().projectKey()).contains(ProjectKey.of(winner.command().projectKey()));
        assertThat(observationCount(first.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void failure_after_actual_insert_rolls_back_both_observation_and_new_project() {
        RememberCommand command = command("rollback", "rollback-project", "valid", OBSERVED);
        RuntimeException failure = new IllegalStateException("test failure after insert");
        RememberMemory remember = useCase(new ForwardingRepository() {
            @Override
            public Observation insertIfAbsent(Observation candidate) {
                super.insertIfAbsent(candidate);
                throw failure;
            }
        });

        assertThatThrownBy(() -> remember.handle(command)).isSameAs(failure);
        assertThat(observationCount(command.idempotencyKey())).isZero();
        assertThat(subjects.findProject(ProjectKey.of(command.projectKey()))).isEmpty();
    }

    private List<Outcome> race(RememberCommand first, RememberCommand second) throws Exception {
        CyclicBarrier lookupsFinished = new CyclicBarrier(2);
        RememberMemory remember = useCase(new ForwardingRepository() {
            @Override
            public Optional<Observation> findByIdempotencyKey(IdempotencyKey key) {
                Optional<Observation> found = super.findByIdempotencyKey(key);
                assertThat(found).isEmpty();
                try {
                    lookupsFinished.await(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("both initial lookups must finish before insert", failure);
                }
                return found;
            }
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstResult = pool.submit(() -> outcome(remember, first));
            var secondResult = pool.submit(() -> outcome(remember, second));
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome outcome(RememberMemory remember, RememberCommand command) {
        try {
            return new Outcome(command, remember.handle(command), null);
        } catch (MemoryException failure) {
            return new Outcome(command, null, failure.code());
        }
    }

    private RememberMemory useCase(ObservationRepository repository) {
        return new RememberMemory(subjects, repository, transactions, CLOCK);
    }

    private RememberCommand command(String key, String project, String content, Instant observedAt) {
        return new RememberCommand(prefix + "-" + key, project == null ? SubjectType.USER : SubjectType.PROJECT,
                project == null ? null : prefix + "-" + project, content, observedAt,
                "example-client", "conversation", "message", 1);
    }

    private long observationCount(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM observation WHERE idempotency_key = ?", Long.class, key);
    }

    private record Outcome(RememberCommand command, RememberResult result, MemoryErrorCode error) {}

    private class ForwardingRepository implements ObservationRepository {
        @Override
        public Optional<Observation> findByIdempotencyKey(IdempotencyKey key) {
            return observations.findByIdempotencyKey(key);
        }

        @Override
        public Observation insertIfAbsent(Observation observation) {
            return observations.insertIfAbsent(observation);
        }

        @Override
        public RecallPage findPage(List<UUID> ids, RecallCursor cursor, int limit) {
            return observations.findPage(ids, cursor, limit);
        }
    }
}
