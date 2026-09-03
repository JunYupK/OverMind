package com.overmind.application.memory;

import com.overmind.domain.memory.SubjectType;
import java.time.Instant;
import java.util.UUID;

/**
 * One observation as recall returns it.
 *
 * <p>Spec §5.2 lists what a recall response must not carry: source conversation and message
 * ids, {@code created_at}, the idempotency key, the USER's internal key, the OIDC subject,
 * and a total count. None of them have a field here — the shape is the enforcement.
 *
 * @param projectKey null for USER-owned observations
 */
public record RecallItem(
        UUID observationId,
        SubjectType subjectType,
        String projectKey,
        String content,
        String client,
        Instant observedAt) {}
