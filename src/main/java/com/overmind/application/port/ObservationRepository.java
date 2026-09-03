package com.overmind.application.port;

import com.overmind.application.memory.RecallCursor;
import com.overmind.application.memory.RecallPage;
import com.overmind.domain.memory.IdempotencyKey;
import com.overmind.domain.memory.Observation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only observation persistence. Forget gets a separate port in M6. */
public interface ObservationRepository {

    Optional<Observation> findByIdempotencyKey(IdempotencyKey key);

    /** Returns the stored observation when an idempotency key already exists. */
    Observation insertIfAbsent(Observation observation);

    RecallPage findPage(List<UUID> subjectIds, RecallCursor cursor, int limit);
}
