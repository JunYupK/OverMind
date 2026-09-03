package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The subject to which a memory belongs. */
public final class MemorySubject {
    private final UUID id;
    private final SubjectType type;
    private final ProjectKey projectKey;

    private MemorySubject(UUID id, SubjectType type, ProjectKey projectKey) {
        this.id = id;
        this.type = type;
        this.projectKey = projectKey;
    }

    public static MemorySubject user(UUID id) {
        return new MemorySubject(requireId(id), SubjectType.USER, null);
    }

    public static MemorySubject project(UUID id, ProjectKey key) {
        if (key == null) {
            throw new DomainValidationException("PROJECT subject에는 project key가 필요합니다");
        }
        return new MemorySubject(requireId(id), SubjectType.PROJECT, key);
    }

    private static UUID requireId(UUID id) {
        if (id == null) {
            throw new DomainValidationException("subject id가 없습니다");
        }
        return id;
    }

    public UUID id() { return id; }
    public SubjectType type() { return type; }
    public Optional<ProjectKey> projectKey() { return Optional.ofNullable(projectKey); }

    @Override
    public boolean equals(Object other) {
        return other instanceof MemorySubject that
                && id.equals(that.id)
                && type == that.type
                && Objects.equals(projectKey, that.projectKey);
    }

    @Override
    public int hashCode() { return Objects.hash(id, type, projectKey); }
}
