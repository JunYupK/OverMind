package com.overmind.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.support.PostgresTestBase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** L2. JPA maps every persistence value losslessly onto the V2 schema. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class JpaMappingTest extends PostgresTestBase {

    @Autowired private SubjectJpaRepository subjects;
    @Autowired private ObservationJpaRepository observations;
    @Autowired private EntityManager entityManager;

    @Test
    void round_trips_subject_and_observation_with_all_persisted_values() {
        UUID subjectId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        Instant subjectCreatedAt = Instant.parse("2026-09-03T00:00:00.123456Z");
        Instant observedAt = OffsetDateTime.parse("2026-09-03T09:10:11.654321+09:00").toInstant();
        Instant observationCreatedAt = Instant.parse("2026-09-03T00:10:12.123456Z");
        String idempotencyKey = "idem ' with spaces ";
        String content = " raw content ' with leading and trailing spaces ";
        String sourceClient = " client ' ";
        String sourceConversationId = " conversation ' ";
        String sourceMessageId = " message ' ";

        subjects.save(new MemorySubjectEntity(subjectId, "PROJECT", "roundtrip-project", subjectCreatedAt));
        entityManager.flush();
        observations.save(
                new ObservationEntity(
                        observationId,
                        subjectId,
                        idempotencyKey,
                        content,
                        observedAt,
                        observationCreatedAt,
                        sourceClient,
                        sourceConversationId,
                        sourceMessageId,
                        "DIRECT_MCP",
                        1));
        entityManager.flush();
        entityManager.clear();

        MemorySubjectEntity subject = subjects.findById(subjectId).orElseThrow();
        ObservationEntity observation = observations.findByIdempotencyKey(idempotencyKey).orElseThrow();

        assertThat(subject.getId()).isEqualTo(subjectId);
        assertThat(subject.getType()).isEqualTo("PROJECT");
        assertThat(subject.getSubjectKey()).isEqualTo("roundtrip-project");
        assertThat(subject.getCreatedAt()).isEqualTo(subjectCreatedAt);
        assertThat(observation.getId()).isEqualTo(observationId);
        assertThat(observation.getSubjectId()).isEqualTo(subjectId);
        assertThat(observation.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(observation.getContent()).isEqualTo(content);
        assertThat(observation.getObservedAt()).isEqualTo(observedAt);
        assertThat(observation.getCreatedAt()).isEqualTo(observationCreatedAt);
        assertThat(observation.getSourceClient()).isEqualTo(sourceClient);
        assertThat(observation.getSourceConversationId()).isEqualTo(sourceConversationId);
        assertThat(observation.getSourceMessageId()).isEqualTo(sourceMessageId);
        assertThat(observation.getIngestionType()).isEqualTo("DIRECT_MCP");
        assertThat(observation.getInputSchemaVersion()).isEqualTo(1);
    }
}
