package com.overmind.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * L1. Spec §10 AC 7 and §4.2: the normal observation path has no update or delete.
 *
 * <p><b>The invariant is enforced by absence,</b> which nothing notices when it stops being
 * true. Adding {@code deleteById} to the port would compile, pass every other test, and
 * silently end the append-only guarantee that §2.2 builds the whole replay story on. This
 * pins the port's surface so that widening it is a deliberate, visible act.
 *
 * <p>Forget arrives in M6 and gets its own port. When it does, this test should be amended to
 * name that port as the sanctioned exception — not deleted.
 */
class ObservationPortShapeTest {

    private static final List<String> ALLOWED_OBSERVATION_METHODS =
            List.of("findByIdempotencyKey", "insertIfAbsent", "findPage");

    private static final List<String> ALLOWED_SUBJECT_METHODS =
            List.of("findOrCreateUser", "findUser", "findOrCreateProject", "findProject");

    /** Substrings that would signal a mutation capability regardless of naming style. */
    private static final List<String> MUTATION_HINTS =
            List.of("update", "delete", "remove", "purge", "forget", "truncate", "drop", "modify");

    @Test
    void the_observation_port_exposes_exactly_the_append_only_surface() {
        assertThat(declaredMethodNames(ObservationRepository.class))
                .as(
                        "spec §4.2 keeps observations append-only by giving the port no way to change"
                                + " them. Widening this surface needs a decision, not a commit")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_OBSERVATION_METHODS);
    }

    @Test
    void the_subject_port_exposes_exactly_the_lookup_and_create_surface() {
        assertThat(declaredMethodNames(SubjectRepository.class))
                .containsExactlyInAnyOrderElementsOf(ALLOWED_SUBJECT_METHODS);
    }

    @Test
    void neither_port_carries_a_method_that_reads_as_a_mutation() {
        // The exact-surface assertions above already pin today's names. This one keeps a
        // renamed or differently-spelled mutation from slipping in with the allow-list edited
        // to match, by refusing the vocabulary itself.
        for (Class<?> port : List.of(ObservationRepository.class, SubjectRepository.class)) {
            for (String name : declaredMethodNames(port)) {
                String folded = name.toLowerCase(Locale.ROOT);
                assertThat(MUTATION_HINTS.stream().noneMatch(folded::contains))
                        .as("%s.%s reads as a mutation; observations are append-only", port.getSimpleName(), name)
                        .isTrue();
            }
        }
    }

    @Test
    void the_scan_actually_sees_the_ports() {
        // A reflection check that silently found nothing would pass every assertion above.
        assertThat(declaredMethodNames(ObservationRepository.class)).isNotEmpty();
        assertThat(declaredMethodNames(SubjectRepository.class)).isNotEmpty();
    }

    private static List<String> declaredMethodNames(Class<?> port) {
        return Arrays.stream(port.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .toList();
    }
}
