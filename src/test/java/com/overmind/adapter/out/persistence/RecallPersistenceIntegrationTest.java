package com.overmind.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.OvermindApplication;
import com.overmind.adapter.out.security.HmacCursorCodec;
import com.overmind.application.memory.RecallItem;
import com.overmind.application.memory.RecallMemory;
import com.overmind.application.memory.RecallQuery;
import com.overmind.application.memory.RecallResult;
import com.overmind.application.memory.RememberCommand;
import com.overmind.application.memory.RememberMemory;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import com.overmind.domain.memory.SubjectType;
import com.overmind.support.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** T6/T7 use cases exercise T8 SQL with signed cursors at real database timestamp precision. */
@Tag("integration")
@SpringBootTest(classes = OvermindApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RecallPersistenceIntegrationTest extends PostgresTestBase {
    private static final String SCHEMA = "t8_recall_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant OBSERVED = Instant.parse("2026-09-02T12:00:00.123456Z");
    @Autowired private SubjectRepository subjects;
    @Autowired private ObservationRepository observations;
    @Autowired private TransactionBoundary transactions;
    @Autowired private JdbcTemplate jdbc;

    private final String prefix = "t8-recall-" + UUID.randomUUID();

    @DynamicPropertySource
    static void schemaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
    }

    @AfterEach
    void deleteOnlyOwnedRows() {
        jdbc.update("DELETE FROM observation WHERE idempotency_key LIKE ?", prefix + "%");
        jdbc.update("DELETE FROM memory_subject WHERE subject_key LIKE ?", prefix + "%");
    }

    @Test
    void budget_cursor_walks_every_selected_observation_using_the_last_returned_position() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        Clock clock = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
        RememberMemory remember = new RememberMemory(subjects, observations, transactions, clock);
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String project = i % 2 == 0 ? null : prefix;
            seeded.add(remember.handle(command("key-" + i, project, "기억" + i, OBSERVED.plusNanos(i * 1000L))).observationId());
        }
        remember.handle(command("excluded", prefix + "-other", "제외", OBSERVED.plusSeconds(1)));
        List<UUID> expected = new ArrayList<>(seeded);
        Collections.reverse(expected);

        // Each content is seven UTF-8 bytes. A smaller injected budget exercises early cutoff:
        // the production 2 MiB budget is unreachable with the current limit and content ceiling.
        RecallMemory recall = new RecallMemory(subjects, observations, new HmacCursorCodec(new byte[32]), 14);
        List<UUID> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            RecallResult result = recall.handle(new RecallQuery(prefix, 6, cursor));
            assertThat(result.observations()).hasSize(2);
            assertThat(result.observations()).extracting(RecallItem::observationId)
                    .containsExactlyElementsOf(expected.subList(page * 2, page * 2 + 2));
            assertThat(result.observations()).allSatisfy(item -> {
                int index = seeded.indexOf(item.observationId());
                assertThat(item.content()).isEqualTo("기억" + index);
                assertThat(item.client()).isEqualTo("example-client");
                assertThat(item.observedAt()).isEqualTo(OBSERVED.plusNanos(index * 1000L));
                assertThat(item.subjectType()).isEqualTo(index % 2 == 0 ? SubjectType.USER : SubjectType.PROJECT);
                assertThat(item.projectKey()).isEqualTo(index % 2 == 0 ? null : prefix);
            });
            result.observations().forEach(item -> walked.add(item.observationId()));
            cursor = result.nextCursor();
            if (page < 2) {
                assertThat(cursor).isNotBlank();
            } else {
                assertThat(cursor).isNull();
            }
        }
        assertThat(walked).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    private RememberCommand command(String key, String project, String content, Instant observedAt) {
        return new RememberCommand(prefix + "-" + key,
                project == null ? SubjectType.USER : SubjectType.PROJECT,
                project, content, observedAt, "example-client", "conversation", "message", 1);
    }
}
