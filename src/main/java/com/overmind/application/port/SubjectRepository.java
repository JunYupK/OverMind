package com.overmind.application.port;

import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.ProjectKey;
import java.util.Optional;

/** Subject lookup and atomic creation. Recall paths do not create a subject. */
public interface SubjectRepository {

    MemorySubject findOrCreateUser();

    /**
     * Looks the USER subject up without creating it.
     *
     * <p>Spec §5.2: recall never creates a subject. Reusing {@code findOrCreateUser} here would
     * insert a USER row on the first read of an empty store, which is a write on a read path.
     */
    Optional<MemorySubject> findUser();

    MemorySubject findOrCreateProject(ProjectKey key);

    Optional<MemorySubject> findProject(ProjectKey key);
}
