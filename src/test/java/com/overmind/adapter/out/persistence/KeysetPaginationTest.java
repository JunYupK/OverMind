package com.overmind.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.OvermindApplication;
import com.overmind.application.memory.RecallCursor;
import com.overmind.application.memory.RecallPage;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.IngestionType;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ObservationContent;
import com.overmind.domain.memory.ProjectKey;
import com.overmind.domain.memory.SourceReference;
import com.overmind.domain.memory.SubjectType;
import com.overmind.support.PostgresTestBase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** L2. Spec §5.3 keyset pages retain a total order across durable PostgreSQL rows. */
@Tag("integration")
@SpringBootTest(classes = OvermindApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KeysetPaginationTest extends PostgresTestBase {
    private static final String SCHEMA = "t8_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant OBSERVED = Instant.parse("2026-09-02T10:00:00.123456Z");
    private static final Instant CREATED = Instant.parse("2026-09-02T10:01:00.123456Z");

    @Autowired private SubjectRepository subjects;
    @Autowired private ObservationRepository observations;
    @Autowired private TransactionBoundary transactions;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> observationIds = new ArrayList<>();
    private final Set<UUID> subjectIds = new HashSet<>();

    @DynamicPropertySource
    static void schemaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
    }

    @BeforeEach
    void usesItsDedicatedSchema() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @AfterEach
    void deleteOnlyOwnedRows() {
        usesItsDedicatedSchema();
        observationIds.forEach(id -> jdbc.update("DELETE FROM observation WHERE id = ?", id));
        subjectIds.forEach(id -> jdbc.update("DELETE FROM memory_subject WHERE id = ?", id));
    }

    @Test
    void all_tied_keysets_walk_in_postgres_uuid_descending_order_without_gaps_or_repeats() {
        MemorySubject user = user();
        UUID low = id("00000000-0000-0000-0000-000000000001");
        UUID belowSign = id("00000000-0000-0000-7fff-ffffffffffff");
        UUID aboveSign = id("00000000-0000-0000-8000-000000000000");
        UUID highest = id("ffffffff-ffff-ffff-ffff-ffffffffffff");
        seed(user, low, "tied-low", OBSERVED, CREATED);
        seed(user, belowSign, "tied-below-sign", OBSERVED, CREATED);
        seed(user, aboveSign, "tied-above-sign", OBSERVED, CREATED);
        seed(user, highest, "tied-highest", OBSERVED, CREATED);

        RecallPage first = observations.findPage(List.of(user.id()), null, 2);
        assertThat(ids(first)).containsExactly(highest, aboveSign);
        RecallPage second =
                observations.findPage(List.of(user.id()), cursorFor(user.id(), first.items().getLast()), 2);

        assertThat(first.hasMore()).isTrue();
        assertThat(ids(second)).containsExactly(belowSign, low);
        assertThat(second.hasMore()).isFalse();
        assertThat(join(ids(first), ids(second)))
                .containsExactly(highest, aboveSign, belowSign, low)
                .doesNotHaveDuplicates();
    }

    @Test
    void created_at_breaks_observed_at_ties_before_id() {
        MemorySubject project = project("created-order");
        UUID oldest = id("11111111-1111-1111-1111-111111111111");
        UUID middle = id("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID newest = id("22222222-2222-2222-2222-222222222222");
        seed(project, oldest, "created-old", OBSERVED, instant("10:01:00.123456"));
        seed(project, middle, "created-middle", OBSERVED, instant("10:01:00.123457"));
        seed(project, newest, "created-new", OBSERVED, instant("10:01:00.123458"));

        RecallPage first = observations.findPage(List.of(project.id()), null, 1);
        assertThat(ids(first)).containsExactly(newest);
        RecallPage second = observations.findPage(
                List.of(project.id()), cursorFor(project.id(), first.items().getLast()), 2);
        assertThat(first.hasMore()).isTrue();
        assertThat(ids(second)).containsExactly(middle, oldest);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void selected_user_and_project_rows_interleave_globally_and_hydrate_the_joined_subject() {
        MemorySubject user = user();
        MemorySubject project = project("interleave-project");
        MemorySubject excluded = project("excluded-project");
        UUID userNewest = id("33333333-3333-3333-3333-333333333333");
        UUID projectMiddle = id("44444444-4444-4444-4444-444444444444");
        UUID userOldest = id("55555555-5555-5555-5555-555555555555");
        UUID ignored = id("66666666-6666-6666-6666-666666666666");
        seed(user, userNewest, "user-newest", instant("10:03:00.123456"), CREATED);
        seed(project, projectMiddle, "project-middle", instant("10:02:00.123456"), CREATED);
        seed(user, userOldest, "user-oldest", instant("10:01:00.123456"), CREATED);
        seed(excluded, ignored, "excluded", instant("10:04:00.123456"), CREATED);

        RecallPage combined = observations.findPage(List.of(user.id(), project.id()), null, 10);

        assertThat(ids(combined)).containsExactly(userNewest, projectMiddle, userOldest);
        Observation hydratedProject = combined.items().get(1);
        assertThat(hydratedProject.content().value()).isEqualTo("project-middle");
        assertThat(hydratedProject.idempotencyKey()).isEqualTo(IdempotencyKey.of("t8-" + projectMiddle));
        assertThat(hydratedProject.observedAt()).isEqualTo(instant("10:02:00.123456"));
        assertThat(hydratedProject.createdAt()).isEqualTo(CREATED);
        assertThat(hydratedProject.source().client()).isEqualTo("t8-client");
        assertThat(hydratedProject.source().conversationId()).isEqualTo("t8-conversation");
        assertThat(hydratedProject.source().messageId()).isEqualTo("t8-message");
        assertThat(hydratedProject.ingestionType()).isEqualTo(IngestionType.DIRECT_MCP);
        assertThat(hydratedProject.inputSchemaVersion()).isEqualTo(1);
        assertThat(hydratedProject.subject().type()).isEqualTo(SubjectType.PROJECT);
        assertThat(hydratedProject.subject().id()).isEqualTo(project.id());
        assertThat(hydratedProject.subject().projectKey()).contains(ProjectKey.of("t8-interleave-project"));
        assertThat(combined.items().getFirst().subject()).isEqualTo(user);
        assertThat(ids(observations.findPage(List.of(user.id()), null, 10)))
                .containsExactly(userNewest, userOldest);
    }

    @Test
    void continuation_excludes_a_newer_insert_but_includes_a_backdated_insert_below_its_cursor() {
        MemorySubject project = project("between-pages");
        UUID newest = id("77777777-7777-7777-7777-777777777777");
        UUID boundary = id("88888888-8888-8888-8888-888888888888");
        UUID oldest = id("99999999-9999-9999-9999-999999999999");
        UUID insertedNewer = id("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID insertedOlder = id("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        seed(project, newest, "newest", instant("10:03:00.123456"), CREATED);
        seed(project, boundary, "boundary", instant("10:02:00.123456"), CREATED);
        seed(project, oldest, "oldest", instant("10:00:00.123456"), CREATED);

        RecallPage first = observations.findPage(List.of(project.id()), null, 2);
        assertThat(ids(first)).containsExactly(newest, boundary);
        seed(project, insertedNewer, "inserted-newer", instant("10:04:00.123456"), CREATED);
        seed(project, insertedOlder, "inserted-older", instant("10:01:00.123456"), CREATED);
        RecallPage second =
                observations.findPage(
                        List.of(project.id()), cursorFor(project.id(), first.items().getLast()), 10);

        assertThat(ids(second)).containsExactly(insertedOlder, oldest);
        assertThat(ids(second)).doesNotContain(insertedNewer, newest, boundary);
    }

    @Test
    void lookahead_reports_more_only_when_the_row_count_exceeds_the_limit() {
        MemorySubject project = project("lookahead");
        assertThat(observations.findPage(List.of(project.id()), null, 3))
                .isEqualTo(new RecallPage(List.of(), false));
        UUID newest = id("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID middle = id("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID oldest = id("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeef");
        UUID fourth = id("ffffffff-ffff-ffff-ffff-fffffffffff0");
        seed(project, newest, "lookahead-newest", instant("10:03:00.123456"), CREATED);
        seed(project, middle, "lookahead-middle", instant("10:02:00.123456"), CREATED);
        seed(project, oldest, "lookahead-oldest", instant("10:01:00.123456"), CREATED);

        assertThat(ids(observations.findPage(List.of(project.id()), null, 4)))
                .containsExactly(newest, middle, oldest);
        assertThat(observations.findPage(List.of(project.id()), null, 4).hasMore()).isFalse();
        assertThat(observations.findPage(List.of(project.id()), null, 3).hasMore()).isFalse();

        seed(project, fourth, "lookahead-fourth", instant("10:00:00.123456"), CREATED);
        RecallPage full = observations.findPage(List.of(project.id()), null, 3);
        RecallPage terminal =
                observations.findPage(
                        List.of(project.id()), cursorFor(project.id(), full.items().getLast()), 3);

        assertThat(ids(full)).containsExactly(newest, middle, oldest);
        assertThat(full.hasMore()).isTrue();
        assertThat(ids(terminal)).containsExactly(fourth);
        assertThat(terminal.hasMore()).isFalse();
    }

    @Test
    void an_empty_subject_filter_returns_an_empty_terminal_page() {
        assertThat(observations.findPage(List.of(), null, 10))
                .isEqualTo(new RecallPage(List.of(), false));
    }

    @Test
    void fk_failure_after_creating_a_project_rolls_the_project_back() {
        ProjectKey key = ProjectKey.of("t8-rollback-" + UUID.randomUUID().toString().substring(0, 8));

        assertThatThrownBy(
                        () ->
                                transactions.inTransaction(
                                        () -> {
                                            subjects.findOrCreateProject(key);
                                            return observations.insertIfAbsent(danglingObservation());
                                        }))
                .satisfies(
                        failure -> {
                            SQLException sql = findSqlException(failure);
                            assertThat((Throwable) sql).as("foreign-key error must escape the real database").isNotNull();
                            assertThat(sql.getSQLState()).isEqualTo("23503");
                            assertThat(sql.getMessage()).contains("observation_subject_fk");
                        });

        assertThat(subjects.findProject(key)).isEmpty();
    }

    private MemorySubject project(String suffix) {
        MemorySubject project = subjects.findOrCreateProject(ProjectKey.of("t8-" + suffix));
        subjectIds.add(project.id());
        return project;
    }

    private MemorySubject user() {
        MemorySubject user = subjects.findOrCreateUser();
        subjectIds.add(user.id());
        return user;
    }

    private void seed(MemorySubject subject, UUID id, String content, Instant observedAt, Instant createdAt) {
        observations.insertIfAbsent(
                Observation.create(
                        id,
                        subject,
                        IdempotencyKey.of("t8-" + id),
                        ObservationContent.of(content),
                        observedAt,
                        createdAt,
                        SourceReference.of("t8-client", "t8-conversation", "t8-message"),
                        IngestionType.DIRECT_MCP,
                        1));
        observationIds.add(id);
    }

    private Observation danglingObservation() {
        MemorySubject absent =
                MemorySubject.project(UUID.randomUUID(), ProjectKey.of("t8-not-inserted"));
        return Observation.create(
                UUID.randomUUID(),
                absent,
                IdempotencyKey.of("t8-dangling-" + UUID.randomUUID()),
                ObservationContent.of("rollback probe"),
                OBSERVED,
                CREATED,
                SourceReference.of("t8-client", "t8-conversation", "t8-message"),
                IngestionType.DIRECT_MCP,
                1);
    }

    private RecallCursor cursorFor(UUID subjectId, Observation observation) {
        return new RecallCursor(
                observation.observedAt(),
                observation.createdAt(),
                observation.id(),
                RecallCursor.filterFingerprint(List.of(subjectId)));
    }

    private static List<UUID> ids(RecallPage page) {
        return page.items().stream().map(Observation::id).toList();
    }

    private static List<UUID> join(List<UUID> first, List<UUID> second) {
        List<UUID> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-09-02T" + time + "Z");
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }

    private static SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
