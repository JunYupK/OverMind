package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An immutable observed fact. Corrections are represented by new observations. */
public final class Observation {
    private final UUID id;
    private final MemorySubject subject;
    private final IdempotencyKey idempotencyKey;
    private final ObservationContent content;
    private final Instant observedAt;
    private final Instant createdAt;
    private final SourceReference source;
    private final IngestionType ingestionType;
    private final int inputSchemaVersion;

    private Observation(UUID id, MemorySubject subject, IdempotencyKey idempotencyKey,
            ObservationContent content, Instant observedAt, Instant createdAt,
            SourceReference source, IngestionType ingestionType, int inputSchemaVersion) {
        this.id = id; this.subject = subject; this.idempotencyKey = idempotencyKey;
        this.content = content; this.observedAt = observedAt; this.createdAt = createdAt;
        this.source = source; this.ingestionType = ingestionType;
        this.inputSchemaVersion = inputSchemaVersion;
    }

    public static Observation create(UUID id, MemorySubject subject, IdempotencyKey idempotencyKey,
            ObservationContent content, Instant observedAt, Instant createdAt,
            SourceReference source, IngestionType ingestionType, int inputSchemaVersion) {
        require(id != null, "observation id");
        require(subject != null, "subject");
        require(idempotencyKey != null, "idempotency key");
        require(content != null, "content");
        require(observedAt != null, "observed_at");
        require(createdAt != null, "created_at");
        require(source != null, "source reference");
        require(ingestionType != null, "ingestion type");
        if (inputSchemaVersion < 1) {
            throw new DomainValidationException("input schema version은 1 이상이어야 합니다");
        }
        return new Observation(id, subject, idempotencyKey, content, observedAt, createdAt,
                source, ingestionType, inputSchemaVersion);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new DomainValidationException(label + " 가 없습니다");
    }

    public UUID id() { return id; }
    public MemorySubject subject() { return subject; }
    public IdempotencyKey idempotencyKey() { return idempotencyKey; }
    public ObservationContent content() { return content; }
    public Instant observedAt() { return observedAt; }
    public Instant createdAt() { return createdAt; }
    public SourceReference source() { return source; }
    public IngestionType ingestionType() { return ingestionType; }
    public int inputSchemaVersion() { return inputSchemaVersion; }

    @Override
    public boolean equals(Object other) {
        return other instanceof Observation that && id.equals(that.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
