package com.overmind.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import com.overmind.domain.DomainValidationException;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.IngestionType;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ObservationContent;
import com.overmind.domain.memory.ProjectKey;
import com.overmind.domain.memory.SourceReference;
import com.overmind.domain.memory.SubjectType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L1. Spec §§2.3, 4.3, and 5.1: remember idempotency and atomicity. */
class RememberMemoryTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-09-02T11:00:00Z");
    private static final String MAGIC = "unrepeatable-secret-content-7091";

    private InMemoryRepositories repos;
    private RememberMemory remember;

    @BeforeEach
    void setUp() {
        repos = new InMemoryRepositories();
        remember = rememberWith(repos.observations());
    }

    @Test
    void a_new_remember_creates_one_observation() {
        RememberResult result = remember.handle(userCommand());

        assertThat(result.created()).isTrue();
        assertThat(result.observationId()).isNotNull();
        assertThat(repos.observationCount()).isEqualTo(1);
    }

    @Test
    void an_identical_retry_returns_the_same_id_and_creates_nothing() {
        RememberResult first = remember.handle(userCommand());
        RememberResult second = remember.handle(userCommand());

        assertThat(second.created()).isFalse();
        assertThat(second.observationId()).isEqualTo(first.observationId());
        assertThat(repos.observationCount()).isEqualTo(1);
    }

    @Test
    void a_project_remember_creates_the_project_subject() {
        remember.handle(projectCommand("idem-p", "overmind", "build uses gradle"));

        assertThat(repos.projectExists("overmind")).isTrue();
    }

    @Test
    void null_command_and_subject_type_are_invalid_arguments() {
        assertInvalidArgument(() -> remember.handle(null));
        assertInvalidArgument(
                () ->
                        remember.handle(
                                new RememberCommand(
                                        "idem-null-type",
                                        null,
                                        null,
                                        "content",
                                        OBSERVED,
                                        "client",
                                        "conversation",
                                        "message",
                                        1)));
    }

    @Test
    void user_must_not_carry_a_project_key() {
        RememberCommand withKey =
                new RememberCommand(
                        "idem-user-key",
                        SubjectType.USER,
                        "overmind",
                        "content",
                        OBSERVED,
                        "client",
                        "conversation",
                        "message",
                        1);

        assertInvalidArgument(() -> remember.handle(withKey));
    }

    @Test
    void project_requires_a_key() {
        RememberCommand noKey =
                new RememberCommand(
                        "idem-project-key",
                        SubjectType.PROJECT,
                        null,
                        "content",
                        OBSERVED,
                        "client",
                        "conversation",
                        "message",
                        1);

        assertInvalidArgument(() -> remember.handle(noKey));
    }

    @Test
    void every_semantic_difference_under_the_same_key_is_a_conflict() {
        remember.handle(userCommand());

        assertConflict(projectCommand("idem-1", "overmind", "coffee is decaf"));
        assertConflict(userCommandWith(" different content", OBSERVED, "client", "conversation", "message", 1));
        assertConflict(userCommandWith("coffee is decaf ", OBSERVED, "client", "conversation", "message", 1));
        assertConflict(userCommandWith("coffee is decaf", OBSERVED.plusSeconds(1), "client", "conversation", "message", 1));
        assertConflict(userCommandWith("coffee is decaf", OBSERVED, "other-client", "conversation", "message", 1));
        assertConflict(userCommandWith("coffee is decaf", OBSERVED, "client", "other-conversation", "message", 1));
        assertConflict(userCommandWith("coffee is decaf", OBSERVED, "client", "conversation", "other-message", 1));
        assertConflict(userCommandWith("coffee is decaf", OBSERVED, "client", "conversation", "message", 2));

        assertThat(repos.observationCount()).isEqualTo(1);
    }

    @Test
    void a_conflicting_project_request_does_not_create_a_project_subject() {
        remember.handle(userCommand());

        assertConflict(projectCommand("idem-1", "not-created", "coffee is decaf"));

        assertThat(repos.projectExists("not-created")).isFalse();
    }

    @Test
    void a_project_key_difference_under_the_same_key_is_a_conflict() {
        remember.handle(projectCommand("idem-project-key", "first-project", "content"));

        assertConflict(projectCommand("idem-project-key", "other-project", "content"));

        assertThat(repos.projectExists("other-project")).isFalse();
    }

    @Test
    void observed_at_more_than_five_minutes_ahead_is_rejected() {
        RememberCommand future =
                userCommandWith(
                        "content",
                        NOW.plus(Duration.ofMinutes(5)).plusMillis(1),
                        "client",
                        "conversation",
                        "message",
                        1);

        assertThatThrownBy(() -> remember.handle(future)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void observed_at_with_sub_microsecond_precision_is_an_invalid_argument() {
        RememberCommand nanosecondPrecision =
                userCommandWith(
                        "content",
                        OBSERVED.plusNanos(1),
                        "client",
                        "conversation",
                        "message",
                        1);

        assertInvalidArgument(() -> remember.handle(nanosecondPrecision));
        assertThat(repos.observationCount()).isZero();
    }

    @Test
    void invalid_timestamp_precision_is_rejected_before_opening_a_transaction() {
        TransactionBoundary rejectingTransactions =
                new TransactionBoundary() {
                    @Override
                    public <T> T inTransaction(java.util.function.Supplier<T> work) {
                        throw new AssertionError("invalid input must not start a transaction");
                    }
                };
        RememberMemory earlyValidation =
                new RememberMemory(
                        repos.subjects(),
                        repos.observations(),
                        rejectingTransactions,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertInvalidArgument(
                () ->
                        earlyValidation.handle(
                                userCommandWith(
                                        "content",
                                        OBSERVED.plusNanos(1),
                                        "client",
                                        "conversation",
                                        "message",
                                        1)));
    }

    @Test
    void an_exact_microsecond_before_epoch_is_preserved() {
        Instant beforeEpoch = Instant.parse("1969-12-31T23:59:59.123456Z");
        RememberResult result =
                remember.handle(
                        userCommandWith(
                                "content", beforeEpoch, "client", "conversation", "message", 1));

        assertThat(repos.observation("idem-1").observedAt()).isEqualTo(beforeEpoch);
        assertThat(result.created()).isTrue();
    }

    @Test
    void a_domain_validation_failure_leaves_no_empty_project_subject() {
        RememberCommand blankContent = projectCommand("idem-blank", "ghost", "   ");

        assertThatThrownBy(() -> remember.handle(blankContent))
                .isInstanceOf(DomainValidationException.class);

        assertThat(repos.projectExists("ghost")).isFalse();
    }

    @Test
    void an_invalid_schema_version_leaves_no_empty_project_subject() {
        RememberCommand badSchema =
                new RememberCommand(
                        "idem-schema",
                        SubjectType.PROJECT,
                        "ghost-schema",
                        "content",
                        OBSERVED,
                        "client",
                        "conversation",
                        "message",
                        0);

        assertThatThrownBy(() -> remember.handle(badSchema)).isInstanceOf(DomainValidationException.class);
        assertThat(repos.projectExists("ghost-schema")).isFalse();
    }

    @Test
    void an_insert_failure_after_subject_creation_leaves_no_empty_project() {
        repos.failNextInsertWith(new RuntimeException("insert failure"));

        assertThatThrownBy(() -> remember.handle(projectCommand("idem-fail", "ghost2", "content")))
                .isInstanceOf(RuntimeException.class);

        assertThat(repos.projectExists("ghost2")).isFalse();
        assertThat(repos.observationCount()).isZero();
    }

    @Test
    void a_race_winner_with_the_same_request_returns_its_result() {
        RememberCommand command = userCommand();
        Observation winner = observationFor(command, MemorySubject.user(UUID.randomUUID()));
        RememberMemory racingRemember = rememberWith(new RaceLosingObservations(repos.observations(), winner));

        RememberResult result = racingRemember.handle(command);

        assertThat(result).isEqualTo(new RememberResult(winner.id(), false));
    }

    @Test
    void a_race_winner_with_a_different_request_is_a_conflict() {
        RememberCommand command = userCommand();
        Observation winner =
                observationFor(
                        userCommandWith(
                                "different", OBSERVED, "client", "conversation", "message", 1),
                        MemorySubject.user(UUID.randomUUID()));
        RememberMemory racingRemember = rememberWith(new RaceLosingObservations(repos.observations(), winner));

        assertConflict(racingRemember, command);
    }

    @Test
    void command_to_string_redacts_every_input_value() {
        RememberCommand command =
                new RememberCommand(
                        MAGIC,
                        SubjectType.PROJECT,
                        "project-" + MAGIC,
                        "content-" + MAGIC,
                        OBSERVED,
                        "client-" + MAGIC,
                        "conversation-" + MAGIC,
                        "message-" + MAGIC,
                        1);

        assertThat(command.toString()).isEqualTo("RememberCommand[redacted]");
        assertThat(command.toString()).doesNotContain(MAGIC);
    }

    @Test
    void invalid_argument_messages_do_not_echo_input_values() {
        RememberCommand command =
                new RememberCommand(
                        "idem-" + MAGIC,
                        SubjectType.USER,
                        MAGIC,
                        "content-" + MAGIC,
                        OBSERVED,
                        "client-" + MAGIC,
                        "conversation-" + MAGIC,
                        "message-" + MAGIC,
                        1);

        assertThatThrownBy(() -> remember.handle(command))
                .isInstanceOf(MemoryException.class)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(MAGIC));
    }

    private RememberMemory rememberWith(ObservationRepository observations) {
        return new RememberMemory(repos.subjects(), observations, repos.transactions(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RememberCommand userCommand() {
        return userCommandWith("coffee is decaf", OBSERVED, "client", "conversation", "message", 1);
    }

    private static RememberCommand userCommandWith(
            String content,
            Instant observedAt,
            String sourceClient,
            String sourceConversationId,
            String sourceMessageId,
            int inputSchemaVersion) {
        return new RememberCommand(
                "idem-1",
                SubjectType.USER,
                null,
                content,
                observedAt,
                sourceClient,
                sourceConversationId,
                sourceMessageId,
                inputSchemaVersion);
    }

    private static RememberCommand projectCommand(String idempotencyKey, String projectKey, String content) {
        return new RememberCommand(
                idempotencyKey,
                SubjectType.PROJECT,
                projectKey,
                content,
                OBSERVED,
                "client",
                "conversation",
                "message",
                1);
    }

    private static Observation observationFor(RememberCommand command, MemorySubject subject) {
        return Observation.create(
                UUID.randomUUID(),
                subject,
                IdempotencyKey.of(command.idempotencyKey()),
                ObservationContent.of(command.content()),
                command.observedAt(),
                NOW,
                SourceReference.of(
                        command.sourceClient(), command.sourceConversationId(), command.sourceMessageId()),
                IngestionType.DIRECT_MCP,
                command.inputSchemaVersion());
    }

    private void assertConflict(RememberCommand command) {
        assertConflict(remember, command);
    }

    private static void assertConflict(RememberMemory useCase, RememberCommand command) {
        assertThatThrownBy(() -> useCase.handle(command))
                .isInstanceOf(MemoryException.class)
                .extracting(exception -> ((MemoryException) exception).code())
                .isEqualTo(MemoryErrorCode.IDEMPOTENCY_CONFLICT);
    }

    private static void assertInvalidArgument(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOf(MemoryException.class)
                .extracting(exception -> ((MemoryException) exception).code())
                .isEqualTo(MemoryErrorCode.INVALID_ARGUMENT);
    }

    private static final class RaceLosingObservations implements ObservationRepository {
        private final ObservationRepository delegate;
        private final Observation winner;

        private RaceLosingObservations(ObservationRepository delegate, Observation winner) {
            this.delegate = delegate;
            this.winner = winner;
        }

        @Override
        public Optional<Observation> findByIdempotencyKey(IdempotencyKey key) {
            return delegate.findByIdempotencyKey(key);
        }

        @Override
        public Observation insertIfAbsent(Observation observation) {
            return winner;
        }

        @Override
        public RecallPage findPage(List<UUID> subjectIds, RecallCursor cursor, int limit) {
            return delegate.findPage(subjectIds, cursor, limit);
        }
    }
}
