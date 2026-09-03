package com.overmind.application.memory;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.port.CursorCodec;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.Observation;
import com.overmind.domain.memory.ProjectKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spec §5.2: read raw observations for USER, optionally merged with one PROJECT.
 *
 * <p>No canonicalization and no semantic search — M0 returns what was stored, newest first.
 */
public class RecallMemory {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 100;

    /**
     * Spec §5.4's page budget.
     *
     * <p><b>Unreachable through the public API at the current numbers.</b> {@link #MAX_LIMIT}
     * observations of {@code ObservationContent.MAX_UTF8_BYTES} each come to 1,638,400 bytes,
     * below this ceiling, so the truncation branch never fires in production. It is kept as a
     * defensive bound and made injectable so the branch itself stays testable;
     * {@code RecallMemoryTest} pins the arithmetic so a later change to either limit surfaces
     * here instead of silently activating dead code.
     */
    public static final int DEFAULT_CONTENT_BUDGET_BYTES = 2 * 1024 * 1024;

    private final SubjectRepository subjects;
    private final ObservationRepository observations;
    private final CursorCodec cursorCodec;
    private final int contentBudgetBytes;

    public RecallMemory(
            SubjectRepository subjects,
            ObservationRepository observations,
            CursorCodec cursorCodec) {
        this(subjects, observations, cursorCodec, DEFAULT_CONTENT_BUDGET_BYTES);
    }

    public RecallMemory(
            SubjectRepository subjects,
            ObservationRepository observations,
            CursorCodec cursorCodec,
            int contentBudgetBytes) {
        if (contentBudgetBytes < 1) {
            throw new IllegalArgumentException("content budget must be positive");
        }
        this.subjects = subjects;
        this.observations = observations;
        this.cursorCodec = cursorCodec;
        this.contentBudgetBytes = contentBudgetBytes;
    }

    public RecallResult handle(RecallQuery query) {
        int limit = normalizeLimit(query.limit());
        List<MemorySubject> selected = selectSubjects(query.projectKey());
        List<UUID> subjectIds = selected.stream().map(MemorySubject::id).toList();

        if (subjectIds.isEmpty()) {
            // Nothing has been remembered yet. An empty page is the honest answer; creating a
            // USER subject to have something to read would be a write on a read path.
            rejectCursorWithoutSubjects(query.cursor());
            return new RecallResult(List.of(), null);
        }

        String fingerprint = RecallCursor.filterFingerprint(subjectIds);
        RecallCursor cursor =
                query.cursor() == null ? null : cursorCodec.decode(query.cursor(), fingerprint);

        RecallPage page = observations.findPage(subjectIds, cursor, limit);
        return buildResult(page, selected, fingerprint);
    }

    private RecallResult buildResult(
            RecallPage page, List<MemorySubject> selected, String fingerprint) {
        List<RecallItem> items = new ArrayList<>();
        Observation last = null;
        long usedBytes = 0;
        boolean truncatedByBudget = false;

        for (Observation observation : page.items()) {
            int size = observation.content().value().getBytes(StandardCharsets.UTF_8).length;
            // Spec §5.4: stop before exceeding the budget, but always return at least one
            // observation. An item larger than the whole budget would otherwise be unreadable.
            if (!items.isEmpty() && usedBytes + size > contentBudgetBytes) {
                truncatedByBudget = true;
                break;
            }
            usedBytes += size;
            items.add(toItem(observation, selected));
            last = observation;
        }

        boolean morePages = truncatedByBudget || page.hasMore();
        String nextCursor =
                morePages && last != null
                        ? cursorCodec.encode(
                                new RecallCursor(
                                        last.observedAt(), last.createdAt(), last.id(), fingerprint))
                        : null;
        return new RecallResult(List.copyOf(items), nextCursor);
    }

    /**
     * Resolves the subjects this query reads, without creating any.
     *
     * <p>USER may legitimately not exist yet; a named PROJECT that does not exist is
     * {@code SUBJECT_NOT_FOUND}, while a PROJECT that exists with no observations is an
     * ordinary empty result (spec §5.2).
     */
    private List<MemorySubject> selectSubjects(String projectKey) {
        List<MemorySubject> selected = new ArrayList<>();
        subjects.findUser().ifPresent(selected::add);
        if (projectKey == null) {
            return selected;
        }
        ProjectKey key = ProjectKey.of(projectKey);
        MemorySubject project =
                subjects.findProject(key)
                        .orElseThrow(
                                () ->
                                        new MemoryException(
                                                MemoryErrorCode.SUBJECT_NOT_FOUND,
                                                "project subject does not exist"));
        selected.add(project);
        return selected;
    }

    private static RecallItem toItem(Observation observation, List<MemorySubject> selected) {
        MemorySubject subject = resolveSubject(observation, selected);
        return new RecallItem(
                observation.id(),
                subject.type(),
                subject.projectKey().map(ProjectKey::value).orElse(null),
                observation.content().value(),
                observation.source().client(),
                observation.observedAt());
    }

    /**
     * Prefers the subject the query resolved over the one carried on the observation.
     *
     * <p>Both should agree. Reading the project key from the resolved set keeps the response
     * consistent with the filter the caller asked for even if a repository returns a subject
     * stub without its key.
     */
    private static MemorySubject resolveSubject(
            Observation observation, List<MemorySubject> selected) {
        UUID id = observation.subject().id();
        return selected.stream()
                .filter(subject -> subject.id().equals(id))
                .findFirst()
                .orElse(observation.subject());
    }

    private static int normalizeLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < MIN_LIMIT || requested > MAX_LIMIT) {
            throw new MemoryException(
                    MemoryErrorCode.INVALID_ARGUMENT,
                    "limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
        return requested;
    }

    /**
     * A cursor presented when no subject resolves cannot be verified against a filter, and
     * quietly ignoring it would restart the walk. Spec §5.3 forbids the silent reset.
     */
    private static void rejectCursorWithoutSubjects(String cursor) {
        if (cursor != null) {
            throw new MemoryException(MemoryErrorCode.INVALID_CURSOR, "cursor is not valid");
        }
    }
}
