package com.overmind.application.memory;

import java.time.Instant;
import java.util.UUID;

/** Recall keyset carrier. Encoding and validation are introduced in Task 6. */
public record RecallCursor(
        Instant observedAt, Instant createdAt, UUID id, String filterFingerprint) {}
