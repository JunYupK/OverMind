package com.overmind.application.memory;

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
import com.overmind.domain.memory.ObservedAtPolicy;
import com.overmind.domain.memory.ProjectKey;
import com.overmind.domain.memory.SourceReference;
import com.overmind.domain.memory.SubjectType;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Spec §5.1: one remember call atomically selects or creates one raw observation. */
public class RememberMemory {

    private final SubjectRepository subjects;
    private final ObservationRepository observations;
    private final TransactionBoundary transactions;
    private final Clock clock;
    private final ObservedAtPolicy observedAtPolicy;

    public RememberMemory(
            SubjectRepository subjects,
            ObservationRepository observations,
            TransactionBoundary transactions,
            Clock clock) {
        this.subjects = subjects;
        this.observations = observations;
        this.transactions = transactions;
        this.clock = clock;
        this.observedAtPolicy = new ObservedAtPolicy(clock);
    }

    public RememberResult handle(RememberCommand command) {
        ValidatedCommand validated = validate(command);
        return transactions.inTransaction(() -> handleInTransaction(validated));
    }

    private ValidatedCommand validate(RememberCommand command) {
        if (command == null || command.subjectType() == null) {
            throw invalidArgument("subject type is required");
        }
        IdempotencyKey key = IdempotencyKey.of(command.idempotencyKey());
        ObservationContent content = ObservationContent.of(command.content());
        SourceReference source =
                SourceReference.of(
                        command.sourceClient(),
                        command.sourceConversationId(),
                        command.sourceMessageId());
        Instant observedAt = observedAtPolicy.validate(command.observedAt());
        validateTimestampPrecision(observedAt);
        validateInputSchemaVersion(command.inputSchemaVersion());
        ProjectKey projectKey = validateSubjectShape(command);
        return new ValidatedCommand(command, key, content, source, observedAt, projectKey);
    }

    private RememberResult handleInTransaction(ValidatedCommand validated) {
        RememberCommand command = validated.command();
        IdempotencyKey key = validated.key();
        ObservationContent content = validated.content();
        SourceReference source = validated.source();
        Instant observedAt = validated.observedAt();
        ProjectKey projectKey = validated.projectKey();

        Optional<Observation> existing = observations.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existingResult(existing.get(), command, content, observedAt, source, projectKey);
        }

        MemorySubject subject =
                projectKey == null
                        ? subjects.findOrCreateUser()
                        : subjects.findOrCreateProject(projectKey);
        UUID candidateId = UUID.randomUUID();
        Observation stored =
                observations.insertIfAbsent(
                        Observation.create(
                                candidateId,
                                subject,
                                key,
                                content,
                                observedAt,
                                clock.instant(),
                                source,
                                IngestionType.DIRECT_MCP,
                                command.inputSchemaVersion()));
        return stored.id().equals(candidateId)
                ? new RememberResult(stored.id(), true)
                : existingResult(stored, command, content, observedAt, source, projectKey);
    }

    private RememberResult existingResult(
            Observation stored,
            RememberCommand command,
            ObservationContent content,
            Instant observedAt,
            SourceReference source,
            ProjectKey projectKey) {
        if (!sameRequest(stored, command, content, observedAt, source, projectKey)) {
            throw new MemoryException(
                    MemoryErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key was already used for a different request");
        }
        return new RememberResult(stored.id(), false);
    }

    private static ProjectKey validateSubjectShape(RememberCommand command) {
        if (command.subjectType() == SubjectType.USER) {
            if (command.projectKey() != null) {
                throw invalidArgument("USER subject cannot include a project key");
            }
            return null;
        }
        if (command.projectKey() == null) {
            throw invalidArgument("PROJECT subject requires a project key");
        }
        return ProjectKey.of(command.projectKey());
    }

    private static void validateTimestampPrecision(Instant observedAt) {
        if (observedAt.getNano() % 1_000 != 0) {
            throw invalidArgument("observed_at precision exceeds microseconds");
        }
    }

    private static void validateInputSchemaVersion(int inputSchemaVersion) {
        if (inputSchemaVersion < 1) {
            throw new DomainValidationException("input schema version은 1 이상이어야 합니다");
        }
    }

    private static boolean sameRequest(
            Observation stored,
            RememberCommand command,
            ObservationContent content,
            Instant observedAt,
            SourceReference source,
            ProjectKey projectKey) {
        return stored.subject().type() == command.subjectType()
                && Objects.equals(stored.subject().projectKey().orElse(null), projectKey)
                && stored.content().equals(content)
                && stored.observedAt().equals(observedAt)
                && stored.source().equals(source)
                && stored.ingestionType() == IngestionType.DIRECT_MCP
                && stored.inputSchemaVersion() == command.inputSchemaVersion();
    }

    private static MemoryException invalidArgument(String message) {
        return new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, message);
    }

    private record ValidatedCommand(
            RememberCommand command,
            IdempotencyKey key,
            ObservationContent content,
            SourceReference source,
            Instant observedAt,
            ProjectKey projectKey) {}
}
