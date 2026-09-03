package com.overmind.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Maps {@code memory_subject}; Hibernate validates this mapping against V2. */
@Entity
@Table(name = "memory_subject")
public class MemorySubjectEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "type", nullable = false, columnDefinition = "text")
    private String type;

    @Column(name = "subject_key", columnDefinition = "text")
    private String subjectKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MemorySubjectEntity() {}

    public MemorySubjectEntity(UUID id, String type, String subjectKey, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.subjectKey = subjectKey;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
