package com.overmind.application.memory;

import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ProjectKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** L1 fake repositories with rollback semantics for transaction-boundary tests. */
final class InMemoryRepositories {

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
                throw new UnsupportedOperationException("Task 7 owns fake cursor paging");
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
