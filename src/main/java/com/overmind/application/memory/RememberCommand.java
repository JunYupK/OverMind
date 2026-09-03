package com.overmind.application.memory;

import com.overmind.domain.memory.SubjectType;
import java.time.Instant;

/** Caller-supplied raw observation request. Its string form deliberately contains no payload. */
public record RememberCommand(
        String idempotencyKey,
        SubjectType subjectType,
        String projectKey,
        String content,
        Instant observedAt,
        String sourceClient,
        String sourceConversationId,
        String sourceMessageId,
        int inputSchemaVersion) {

    @Override
    public String toString() {
        return "RememberCommand[redacted]";
    }
}
