package com.overmind.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.domain.DomainValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemorySubjectTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void user_has_no_project_key() {
        MemorySubject subject = MemorySubject.user(ID);
        assertThat(subject.id()).isEqualTo(ID);
        assertThat(subject.type()).isEqualTo(SubjectType.USER);
        assertThat(subject.projectKey()).isEmpty();
    }

    @Test
    void project_carries_its_key() {
        ProjectKey key = ProjectKey.of("overmind");
        MemorySubject subject = MemorySubject.project(ID, key);
        assertThat(subject.type()).isEqualTo(SubjectType.PROJECT);
        assertThat(subject.projectKey()).contains(key);
    }

    @Test
    void rejects_missing_identity_or_project_key() {
        assertThatThrownBy(() -> MemorySubject.user(null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> MemorySubject.project(ID, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void equality_includes_id_type_and_project_key() {
        assertThat(MemorySubject.user(ID)).isEqualTo(MemorySubject.user(ID));
        assertThat(MemorySubject.user(ID)).isNotEqualTo(MemorySubject.user(UUID.randomUUID()));
        assertThat(MemorySubject.user(ID)).isNotEqualTo(MemorySubject.project(ID, ProjectKey.of("x")));
        assertThat(MemorySubject.project(ID, ProjectKey.of("x")))
                .isNotEqualTo(MemorySubject.project(ID, ProjectKey.of("y")));
        assertThat(MemorySubject.project(ID, ProjectKey.of("x")))
                .hasSameHashCodeAs(MemorySubject.project(ID, ProjectKey.of("x")));
    }
}
