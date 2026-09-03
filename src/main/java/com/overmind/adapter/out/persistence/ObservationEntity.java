package com.overmind.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Maps the append-only {@code observation} table without a JPA subject relationship. */
@Entity
@Table(name = "observation")
public class ObservationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "idempotency_key", nullable = false, columnDefinition = "text")
    private String idempotencyKey;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "source_client", nullable = false, columnDefinition = "text")
    private String sourceClient;

    @Column(name = "source_conversation_id", nullable = false, columnDefinition = "text")
    private String sourceConversationId;

    @Column(name = "source_message_id", nullable = false, columnDefinition = "text")
    private String sourceMessageId;

    @Column(name = "ingestion_type", nullable = false, columnDefinition = "text")
    private String ingestionType;

    @Column(name = "input_schema_version", nullable = false)
    private int inputSchemaVersion;

    protected ObservationEntity() {}

    public ObservationEntity(
            UUID id,
            UUID subjectId,
            String idempotencyKey,
            String content,
            Instant observedAt,
            Instant createdAt,
            String sourceClient,
            String sourceConversationId,
            String sourceMessageId,
            String ingestionType,
            int inputSchemaVersion) {
        this.id = id;
        this.subjectId = subjectId;
        this.idempotencyKey = idempotencyKey;
        this.content = content;
        this.observedAt = observedAt;
        this.createdAt = createdAt;
        this.sourceClient = sourceClient;
        this.sourceConversationId = sourceConversationId;
        this.sourceMessageId = sourceMessageId;
        this.ingestionType = ingestionType;
        this.inputSchemaVersion = inputSchemaVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getContent() {
        return content;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSourceClient() {
        return sourceClient;
    }

    public String getSourceConversationId() {
        return sourceConversationId;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }

    public String getIngestionType() {
        return ingestionType;
    }

    public int getInputSchemaVersion() {
        return inputSchemaVersion;
    }
}
