package com.overmind.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.domain.DomainValidationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservationTest {
    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final MemorySubject SUBJECT = MemorySubject.user(UUID.randomUUID());
    private static final IdempotencyKey KEY = IdempotencyKey.of("request-1");
    private static final ObservationContent CONTENT = ObservationContent.of(" exact payload ");
    private static final Instant OBSERVED = Instant.parse("2026-09-02T11:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-09-02T12:00:00Z");
    private static final SourceReference SOURCE = SourceReference.of("client", "conversation", "message");

    @Test
    void create_preserves_all_supplied_values() {
        Observation observation = create(3);
        assertThat(observation.id()).isEqualTo(ID);
        assertThat(observation.subject()).isSameAs(SUBJECT);
        assertThat(observation.idempotencyKey()).isSameAs(KEY);
        assertThat(observation.content()).isSameAs(CONTENT);
        assertThat(observation.observedAt()).isSameAs(OBSERVED);
        assertThat(observation.createdAt()).isSameAs(CREATED);
        assertThat(observation.source()).isSameAs(SOURCE);
        assertThat(observation.ingestionType()).isEqualTo(IngestionType.DIRECT_MCP);
        assertThat(observation.inputSchemaVersion()).isEqualTo(3);
    }

    @Test
    void create_rejects_each_required_null_field() {
        Object[][] cases = {
            {null, SUBJECT, KEY, CONTENT, OBSERVED, CREATED, SOURCE, IngestionType.DIRECT_MCP},
            {ID, null, KEY, CONTENT, OBSERVED, CREATED, SOURCE, IngestionType.DIRECT_MCP},
            {ID, SUBJECT, null, CONTENT, OBSERVED, CREATED, SOURCE, IngestionType.DIRECT_MCP},
            {ID, SUBJECT, KEY, null, OBSERVED, CREATED, SOURCE, IngestionType.DIRECT_MCP},
            {ID, SUBJECT, KEY, CONTENT, null, CREATED, SOURCE, IngestionType.DIRECT_MCP},
            {ID, SUBJECT, KEY, CONTENT, OBSERVED, null, SOURCE, IngestionType.DIRECT_MCP},
            {ID, SUBJECT, KEY, CONTENT, OBSERVED, CREATED, null, IngestionType.DIRECT_MCP},
            {ID, SUBJECT, KEY, CONTENT, OBSERVED, CREATED, SOURCE, null}
        };
        for (Object[] values : cases) {
            assertThatThrownBy(() -> Observation.create(
                            (UUID) values[0], (MemorySubject) values[1], (IdempotencyKey) values[2],
                            (ObservationContent) values[3], (Instant) values[4], (Instant) values[5],
                            (SourceReference) values[6], (IngestionType) values[7], 1))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Test
    void create_rejects_schema_versions_below_one() {
        assertThatThrownBy(() -> create(0)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equality_is_based_on_observation_id() {
        assertThat(create(1)).isEqualTo(create(2));
        assertThat(create(1)).hasSameHashCodeAs(create(2));
        Observation other = Observation.create(UUID.randomUUID(), SUBJECT, KEY, CONTENT, OBSERVED, CREATED, SOURCE,
                IngestionType.DIRECT_MCP, 1);
        assertThat(create(1)).isNotEqualTo(other);
    }

    private static Observation create(int version) {
        return Observation.create(ID, SUBJECT, KEY, CONTENT, OBSERVED, CREATED, SOURCE,
                IngestionType.DIRECT_MCP, version);
    }
}
