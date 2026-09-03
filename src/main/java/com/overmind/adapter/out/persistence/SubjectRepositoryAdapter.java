package com.overmind.adapter.out.persistence;

import com.overmind.application.port.SubjectRepository;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.ProjectKey;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed subject repository with conflict-specific insert-or-find operations. */
@Repository
public class SubjectRepositoryAdapter implements SubjectRepository {

    private final EntityManager entityManager;
    private final Clock clock;

    public SubjectRepositoryAdapter(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    @Transactional
    public MemorySubject findOrCreateUser() {
        List<?> inserted =
                entityManager
                        .createNativeQuery(
                                "INSERT INTO memory_subject (id, type, subject_key, created_at) "
                                        + "VALUES (:id, 'USER', NULL, :createdAt) "
                                        + "ON CONFLICT ((true)) WHERE type = 'USER' DO NOTHING RETURNING id")
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("createdAt", clock.instant())
                        .getResultList();
        UUID id = inserted.isEmpty() ? selectUserId() : toUuid(inserted.getFirst());
        return MemorySubject.user(id);
    }

    @Override
    @Transactional
    public MemorySubject findOrCreateProject(ProjectKey key) {
        List<?> inserted =
                entityManager
                        .createNativeQuery(
                                "INSERT INTO memory_subject (id, type, subject_key, created_at) "
                                        + "VALUES (:id, 'PROJECT', :subjectKey, :createdAt) "
                                        + "ON CONFLICT (type, subject_key) DO NOTHING RETURNING id")
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("subjectKey", key.value())
                        .setParameter("createdAt", clock.instant())
                        .getResultList();
        UUID id = inserted.isEmpty() ? selectProjectId(key) : toUuid(inserted.getFirst());
        return MemorySubject.project(id, key);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemorySubject> findUser() {
        List<?> rows =
                entityManager
                        .createNativeQuery("SELECT id FROM memory_subject WHERE type = 'USER'")
                        .getResultList();
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(MemorySubject.user(toUuid(rows.getFirst())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemorySubject> findProject(ProjectKey key) {
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT id FROM memory_subject "
                                        + "WHERE type = 'PROJECT' AND subject_key = :subjectKey")
                        .setParameter("subjectKey", key.value())
                        .getResultList();
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(MemorySubject.project(toUuid(rows.getFirst()), key));
    }

    private UUID selectUserId() {
        return toUuid(
                entityManager
                        .createNativeQuery("SELECT id FROM memory_subject WHERE type = 'USER'")
                        .getSingleResult());
    }

    private UUID selectProjectId(ProjectKey key) {
        return toUuid(
                entityManager
                        .createNativeQuery(
                                "SELECT id FROM memory_subject "
                                        + "WHERE type = 'PROJECT' AND subject_key = :subjectKey")
                        .setParameter("subjectKey", key.value())
                        .getSingleResult());
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }
}
