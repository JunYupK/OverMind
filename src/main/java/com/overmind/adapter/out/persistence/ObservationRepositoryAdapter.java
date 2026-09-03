package com.overmind.adapter.out.persistence;

import com.overmind.application.memory.RecallCursor;
import com.overmind.application.memory.RecallPage;
import com.overmind.application.port.ObservationRepository;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.IngestionType;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ObservationContent;
import com.overmind.domain.memory.ProjectKey;
import com.overmind.domain.memory.SourceReference;
import com.overmind.domain.memory.SubjectType;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed append-only observation repository. */
@Repository
public class ObservationRepositoryAdapter implements ObservationRepository {

    private final EntityManager entityManager;
    private final ObservationJpaRepository observations;
    private final SubjectJpaRepository subjects;

    public ObservationRepositoryAdapter(
            EntityManager entityManager,
            ObservationJpaRepository observations,
            SubjectJpaRepository subjects) {
        this.entityManager = entityManager;
        this.observations = observations;
        this.subjects = subjects;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Observation> findByIdempotencyKey(IdempotencyKey key) {
        return observations.findByIdempotencyKey(key.value()).map(this::toDomain);
    }

    @Override
    @Transactional
    public Observation insertIfAbsent(Observation observation) {
        List<?> inserted =
                entityManager
                        .createNativeQuery(
                                "INSERT INTO observation (id, subject_id, idempotency_key, content, "
                                        + "observed_at, created_at, source_client, source_conversation_id, "
                                        + "source_message_id, ingestion_type, input_schema_version) "
                                        + "VALUES (:id, :subjectId, :idempotencyKey, :content, :observedAt, "
                                        + ":createdAt, :sourceClient, :sourceConversationId, :sourceMessageId, "
                                        + ":ingestionType, :inputSchemaVersion) "
                                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")
                        .setParameter("id", observation.id())
                        .setParameter("subjectId", observation.subject().id())
                        .setParameter("idempotencyKey", observation.idempotencyKey().value())
                        .setParameter("content", observation.content().value())
                        .setParameter("observedAt", observation.observedAt())
                        .setParameter("createdAt", observation.createdAt())
                        .setParameter("sourceClient", observation.source().client())
                        .setParameter("sourceConversationId", observation.source().conversationId())
                        .setParameter("sourceMessageId", observation.source().messageId())
                        .setParameter("ingestionType", observation.ingestionType().name())
                        .setParameter("inputSchemaVersion", observation.inputSchemaVersion())
                        .getResultList();
        if (!inserted.isEmpty()) {
            return observation;
        }
        return findByIdempotencyKey(observation.idempotencyKey()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public RecallPage findPage(List<UUID> subjectIds, RecallCursor cursor, int limit) {
        // Task 8 replaces this deliberate placeholder with the keyset query.
        return new RecallPage(List.of(), false);
    }

    private Observation toDomain(ObservationEntity entity) {
        MemorySubject subject =
                subjects
                        .findById(entity.getSubjectId())
                        .map(this::toDomain)
                        .orElseThrow();
        return Observation.create(
                entity.getId(),
                subject,
                IdempotencyKey.of(entity.getIdempotencyKey()),
                ObservationContent.of(entity.getContent()),
                entity.getObservedAt(),
                entity.getCreatedAt(),
                SourceReference.of(
                        entity.getSourceClient(),
                        entity.getSourceConversationId(),
                        entity.getSourceMessageId()),
                IngestionType.valueOf(entity.getIngestionType()),
                entity.getInputSchemaVersion());
    }

    private MemorySubject toDomain(MemorySubjectEntity entity) {
        if (SubjectType.USER.name().equals(entity.getType())) {
            return MemorySubject.user(entity.getId());
        }
        if (SubjectType.PROJECT.name().equals(entity.getType())) {
            return MemorySubject.project(entity.getId(), ProjectKey.of(entity.getSubjectKey()));
        }
        throw new IllegalStateException("unknown memory subject type");
    }
}
