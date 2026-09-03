package com.overmind.application.memory;

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
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** L1 fake repositories with rollback semantics for transaction-boundary tests. */
final class InMemoryRepositories {

    private static final Instant SEED_EPOCH = Instant.parse("2020-01-01T00:00:00Z");

    private static final Comparator<Observation> NEWEST_FIRST =
            Comparator.comparing(Observation::observedAt)
                    .thenComparing(Observation::createdAt)
                    .thenComparing(Observation::id)
                    .reversed();

    private final AtomicLong seedSequence = new AtomicLong();
    private final Map<String, MemorySubject> subjectsByKey = new ConcurrentHashMap<>();
    private final Map<String, Observation> observationsByKey = new ConcurrentHashMap<>();
    private RuntimeException nextInsertFailure;

    SubjectRepository subjects() {
        return new SubjectRepository() {
            @Override
            public MemorySubject findOrCreateUser() {
                return subjectsByKey.computeIfAbsent("USER", ignored -> MemorySubject.user(UUID.randomUUID()));
            }

            @Override
            public MemorySubject findOrCreateProject(ProjectKey key) {
                return subjectsByKey.computeIfAbsent(
                        projectSubjectKey(key), ignored -> MemorySubject.project(UUID.randomUUID(), key));
            }

            @Override
            public Optional<MemorySubject> findUser() {
                return Optional.ofNullable(subjectsByKey.get("USER"));
            }

            @Override
            public Optional<MemorySubject> findProject(ProjectKey key) {
                return Optional.ofNullable(subjectsByKey.get(projectSubjectKey(key)));
            }
        };
    }

    ObservationRepository observations() {
        return new ObservationRepository() {
            @Override
            public Optional<Observation> findByIdempotencyKey(IdempotencyKey key) {
                return Optional.ofNullable(observationsByKey.get(key.value()));
            }

            @Override
            public Observation insertIfAbsent(Observation observation) {
                if (nextInsertFailure != null) {
                    RuntimeException failure = nextInsertFailure;
                    nextInsertFailure = null;
                    throw failure;
                }
                observationsByKey.putIfAbsent(observation.idempotencyKey().value(), observation);
                return observationsByKey.get(observation.idempotencyKey().value());
            }

            @Override
            public RecallPage findPage(List<UUID> subjectIds, RecallCursor cursor, int limit) {
                // Mirrors the adapter contract of Task 8: newest first by
                // (observed_at, created_at, id), strictly after the cursor, reading limit+1
                // rows so the caller learns whether another page exists.
                List<Observation> ordered =
                        observationsByKey.values().stream()
                                .filter(o -> subjectIds.contains(o.subject().id()))
                                .filter(o -> cursor == null || isBefore(o, cursor))
                                .sorted(NEWEST_FIRST)
                                .limit(limit + 1L)
                                .toList();
                boolean hasMore = ordered.size() > limit;
                return new RecallPage(
                        hasMore ? List.copyOf(ordered.subList(0, limit)) : List.copyOf(ordered),
                        hasMore);
            }
        };
    }

    TransactionBoundary transactions() {
        return new TransactionBoundary() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> work) {
                Map<String, MemorySubject> subjectsBefore = new LinkedHashMap<>(subjectsByKey);
                Map<String, Observation> observationsBefore = new LinkedHashMap<>(observationsByKey);
                try {
                    return work.get();
                } catch (RuntimeException exception) {
                    subjectsByKey.clear();
                    subjectsByKey.putAll(subjectsBefore);
                    observationsByKey.clear();
                    observationsByKey.putAll(observationsBefore);
                    throw exception;
                }
            }
        };
    }

    /** Seeds one USER observation, creating the USER subject if this is the first one. */
    Observation seedUserObservation(String content, Instant observedAt) {
        return seed(subjects().findOrCreateUser(), content, observedAt);
    }

    /** Seeds one PROJECT observation, creating the PROJECT subject if needed. */
    Observation seedProjectObservation(String projectKey, String content, Instant observedAt) {
        return seed(subjects().findOrCreateProject(ProjectKey.of(projectKey)), content, observedAt);
    }

    /** Creates a PROJECT subject with no observations — spec §5.2's empty-but-present case. */
    void seedProject(String projectKey) {
        subjects().findOrCreateProject(ProjectKey.of(projectKey));
    }

    private Observation seed(MemorySubject subject, String content, Instant observedAt) {
        UUID id = UUID.randomUUID();
        Observation observation =
                Observation.create(
                        id,
                        subject,
                        IdempotencyKey.of("seed-" + id),
                        ObservationContent.of(content),
                        observedAt,
                        // created_at rises with insertion order so equal observed_at values
                        // still order deterministically.
                        SEED_EPOCH.plusMillis(seedSequence.incrementAndGet()),
                        SourceReference.of("example-client", "conv", "msg"),
                        IngestionType.DIRECT_MCP,
                        1);
        observationsByKey.put(observation.idempotencyKey().value(), observation);
        return observation;
    }

    private static boolean isBefore(Observation observation, RecallCursor cursor) {
        int byObservedAt = observation.observedAt().compareTo(cursor.observedAt());
        if (byObservedAt != 0) {
            return byObservedAt < 0;
        }
        int byCreatedAt = observation.createdAt().compareTo(cursor.createdAt());
        if (byCreatedAt != 0) {
            return byCreatedAt < 0;
        }
        return observation.id().compareTo(cursor.id()) < 0;
    }

    int observationCount() {
        return observationsByKey.size();
    }

    boolean projectExists(String key) {
        return subjectsByKey.containsKey("PROJECT:" + key);
    }

    Observation observation(String idempotencyKey) {
        return observationsByKey.get(idempotencyKey);
    }

    void failNextInsertWith(RuntimeException failure) {
        nextInsertFailure = failure;
    }

    private static String projectSubjectKey(ProjectKey key) {
        return "PROJECT:" + key.value();
    }
}
