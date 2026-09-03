package com.overmind.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.adapter.out.security.HmacCursorCodec;
import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.port.CursorCodec;
import com.overmind.domain.DomainValidationException;
import com.overmind.domain.memory.ObservationContent;
import com.overmind.domain.memory.SubjectType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L1. Spec §5.2 and §5.4.
 *
 * <p>Uses the real {@link HmacCursorCodec} rather than a stub: round-tripping through a stub
 * would prove nothing about the cursors this use case actually mints. ArchUnit's layer rules
 * exclude tests, so reaching into the adapter here does not weaken AR-2.
 */
class RecallMemoryTest {

    private static final Instant BASE = Instant.parse("2026-09-02T00:00:00Z");

    private InMemoryRepositories repos;
    private RecallMemory recall;

    private static CursorCodec codec() {
        return new HmacCursorCodec(
                "overmind-test-cursor-key-".repeat(2).getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        repos = new InMemoryRepositories();
        recall = new RecallMemory(repos.subjects(), repos.observations(), codec());
    }

    private RecallMemory withBudget(int budgetBytes) {
        return new RecallMemory(repos.subjects(), repos.observations(), codec(), budgetBytes);
    }

    private static List<String> contentsOf(RecallResult result) {
        return result.observations().stream().map(RecallItem::content).toList();
    }

    @Test
    void without_a_project_key_only_user_observations_come_back() {
        repos.seedUserObservation("user fact", BASE.plusSeconds(10));
        repos.seedProjectObservation("overmind", "project fact", BASE.plusSeconds(20));

        assertThat(contentsOf(recall.handle(new RecallQuery(null, null, null))))
                .containsExactly("user fact");
    }

    @Test
    void with_a_project_key_user_and_project_merge_newest_first() {
        repos.seedUserObservation("older user", BASE.plusSeconds(10));
        repos.seedProjectObservation("overmind", "middle project", BASE.plusSeconds(20));
        repos.seedUserObservation("newest user", BASE.plusSeconds(30));

        assertThat(contentsOf(recall.handle(new RecallQuery("overmind", null, null))))
                .containsExactly("newest user", "middle project", "older user");
    }

    @Test
    void only_the_named_project_joins_the_read() {
        repos.seedProjectObservation("wanted", "wanted fact", BASE.plusSeconds(10));
        repos.seedProjectObservation("other", "other fact", BASE.plusSeconds(20));

        assertThat(contentsOf(recall.handle(new RecallQuery("wanted", null, null))))
                .containsExactly("wanted fact");
    }

    @Test
    void an_unknown_project_is_not_found_and_is_not_created() {
        assertCode(
                () -> recall.handle(new RecallQuery("ghost", null, null)),
                MemoryErrorCode.SUBJECT_NOT_FOUND);

        assertThat(repos.projectExists("ghost")).isFalse();
    }

    @Test
    void a_malformed_project_key_is_rejected_by_the_domain() {
        assertThatThrownBy(() -> recall.handle(new RecallQuery("NotLowercase", null, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void an_existing_project_with_no_observations_is_an_empty_success() {
        repos.seedProject("empty-project");

        RecallResult result = recall.handle(new RecallQuery("empty-project", null, null));

        assertThat(result.observations()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void an_empty_store_reads_empty_without_creating_a_user_subject() {
        RecallResult result = recall.handle(new RecallQuery(null, null, null));

        assertThat(result.observations()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(repos.subjects().findUser())
                .as("spec §5.2: recall must not create a subject")
                .isEmpty();
    }

    @Test
    void the_default_limit_is_twenty() {
        for (int i = 0; i < 25; i++) {
            repos.seedUserObservation("fact " + i, BASE.plusSeconds(i));
        }

        assertThat(recall.handle(new RecallQuery(null, null, null)).observations()).hasSize(20);
    }

    @Test
    void limit_outside_one_to_one_hundred_is_invalid() {
        for (int bad : new int[] {0, -1, 101, Integer.MAX_VALUE}) {
            assertCode(
                    () -> recall.handle(new RecallQuery(null, bad, null)),
                    MemoryErrorCode.INVALID_ARGUMENT);
        }
    }

    @Test
    void the_limit_boundaries_are_accepted() {
        repos.seedUserObservation("only", BASE);

        assertThat(recall.handle(new RecallQuery(null, 1, null)).observations()).hasSize(1);
        assertThat(recall.handle(new RecallQuery(null, 100, null)).observations()).hasSize(1);
    }

    @Test
    void the_response_carries_only_the_fields_spec_5_2_allows() {
        repos.seedUserObservation("fact", BASE);

        RecallItem item = recall.handle(new RecallQuery(null, null, null)).observations().get(0);

        assertThat(item.subjectType()).isEqualTo(SubjectType.USER);
        assertThat(item.projectKey()).isNull();
        assertThat(item.client()).isEqualTo("example-client");
        assertThat(item.content()).isEqualTo("fact");
        assertThat(item.observedAt()).isEqualTo(BASE);
    }

    @Test
    void a_project_item_reports_its_key() {
        repos.seedProjectObservation("overmind", "project fact", BASE);

        RecallItem item =
                recall.handle(new RecallQuery("overmind", null, null)).observations().get(0);

        assertThat(item.subjectType()).isEqualTo(SubjectType.PROJECT);
        assertThat(item.projectKey()).isEqualTo("overmind");
    }

    @Test
    void a_cursor_walks_every_observation_exactly_once() {
        for (int i = 0; i < 25; i++) {
            repos.seedUserObservation("fact " + i, BASE.plusSeconds(i));
        }

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            RecallResult result = recall.handle(new RecallQuery(null, 10, cursor));
            walked.addAll(contentsOf(result));
            cursor = result.nextCursor();
            if (cursor == null) {
                break;
            }
        }

        assertThat(walked).doesNotHaveDuplicates().hasSize(25);
        assertThat(cursor).as("the last page must not offer a further cursor").isNull();
    }

    @Test
    void the_last_page_reports_no_next_cursor() {
        repos.seedUserObservation("only", BASE);

        assertThat(recall.handle(new RecallQuery(null, 10, null)).nextCursor()).isNull();
    }

    @Test
    void a_cursor_minted_for_another_filter_is_rejected() {
        repos.seedUserObservation("user fact", BASE.plusSeconds(10));
        repos.seedProjectObservation("overmind", "project fact", BASE.plusSeconds(20));
        String userOnlyCursor =
                recall.handle(new RecallQuery(null, 1, null)).nextCursor();

        assertThat(userOnlyCursor).isNull();

        for (int i = 0; i < 3; i++) {
            repos.seedUserObservation("filler " + i, BASE.plusSeconds(30 + i));
        }
        String cursor = recall.handle(new RecallQuery(null, 1, null)).nextCursor();
        assertThat(cursor).isNotNull();

        assertCode(
                () -> recall.handle(new RecallQuery("overmind", 1, cursor)),
                MemoryErrorCode.INVALID_CURSOR);
    }

    @Test
    void a_forged_cursor_is_rejected_rather_than_restarting_the_walk() {
        repos.seedUserObservation("fact", BASE);

        assertCode(
                () -> recall.handle(new RecallQuery(null, 10, "v1.forged.signature")),
                MemoryErrorCode.INVALID_CURSOR);
    }

    @Test
    void a_cursor_presented_against_an_empty_store_is_rejected() {
        assertCode(
                () -> recall.handle(new RecallQuery(null, 10, "v1.anything.here")),
                MemoryErrorCode.INVALID_CURSOR);
    }

    @Test
    void the_content_budget_ends_the_page_before_the_limit_does() {
        String chunk = "a".repeat(4 * 1024);
        for (int i = 0; i < 10; i++) {
            repos.seedUserObservation(chunk, BASE.plusSeconds(i));
        }
        RecallMemory budgeted = withBudget(10 * 1024);

        RecallResult first = budgeted.handle(new RecallQuery(null, 10, null));

        assertThat(first.observations())
                .as("two 4 KiB items fit in 10 KiB; a third would exceed it")
                .hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        RecallResult second = budgeted.handle(new RecallQuery(null, 10, first.nextCursor()));

        assertThat(second.observations()).isNotEmpty();
        assertThat(second.observations())
                .extracting(RecallItem::observationId)
                .doesNotContainAnyElementsOf(
                        first.observations().stream().map(RecallItem::observationId).toList());
    }

    @Test
    void the_budget_cursor_resumes_from_the_last_item_actually_returned() {
        String chunk = "b".repeat(4 * 1024);
        for (int i = 0; i < 6; i++) {
            repos.seedUserObservation(chunk + " " + i, BASE.plusSeconds(i));
        }
        RecallMemory budgeted = withBudget(10 * 1024);

        List<UUID> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 6; page++) {
            RecallResult result = budgeted.handle(new RecallQuery(null, 10, cursor));
            result.observations().forEach(item -> walked.add(item.observationId()));
            cursor = result.nextCursor();
            if (cursor == null) {
                break;
            }
        }

        assertThat(walked).doesNotHaveDuplicates().hasSize(6);
    }

    @Test
    void an_observation_larger_than_the_budget_is_still_returned() {
        repos.seedUserObservation("c".repeat(16 * 1024), BASE);
        RecallMemory budgeted = withBudget(1024);

        assertThat(budgeted.handle(new RecallQuery(null, 1, null)).observations()).hasSize(1);
    }

    @Test
    void the_production_budget_cannot_be_reached_through_the_public_api() {
        // Pins spec §5.4's arithmetic. If MAX_LIMIT or the content ceiling changes so that a
        // page can exceed the budget, this fails and the "defensive only" comment on
        // RecallMemory.DEFAULT_CONTENT_BUDGET_BYTES must be revisited.
        long largestPossiblePage =
                (long) RecallMemory.MAX_LIMIT * ObservationContent.MAX_UTF8_BYTES;

        assertThat(largestPossiblePage)
                .as("spec §5.4 budget is unreachable at the current §5.2 and §4.2 limits")
                .isLessThan(RecallMemory.DEFAULT_CONTENT_BUDGET_BYTES);
    }

    @Test
    void a_zero_or_negative_budget_is_refused_at_construction() {
        assertThatThrownBy(() -> withBudget(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withBudget(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MemoryErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(MemoryException.class)
                .extracting(thrown -> ((MemoryException) thrown).code())
                .isEqualTo(expected);
    }
}
