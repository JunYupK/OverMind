package com.overmind.application.port;

import com.overmind.domain.memory.MemorySubject;
import com.overmind.domain.memory.ProjectKey;
import java.util.Optional;

/** Subject lookup and atomic creation. Recall paths do not create a subject. */
public interface SubjectRepository {

    MemorySubject findOrCreateUser();

    MemorySubject findOrCreateProject(ProjectKey key);

    Optional<MemorySubject> findProject(ProjectKey key);
}
