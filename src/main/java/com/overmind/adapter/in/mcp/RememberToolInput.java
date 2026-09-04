package com.overmind.adapter.in.mcp;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.memory.RememberCommand;
import com.overmind.domain.memory.SubjectType;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Raw MCP arguments for {@code remember_memory}, converted without coercing caller values. */
final class RememberToolInput {

    private static final Pattern RFC_3339_OFFSET =
            Pattern.compile(
                    "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$",
                    Pattern.CASE_INSENSITIVE);

    private final String idempotencyKey;
    private final SubjectType subjectType;
    private final String projectKey;
    private final String content;
    private final OffsetDateTime observedAt;
    private final String sourceClient;
    private final String sourceConversationId;
    private final String sourceMessageId;
    private final int inputSchemaVersion;

    private RememberToolInput(
            String idempotencyKey,
            SubjectType subjectType,
            String projectKey,
            String content,
            OffsetDateTime observedAt,
            String sourceClient,
            String sourceConversationId,
            String sourceMessageId,
            int inputSchemaVersion) {
        this.idempotencyKey = idempotencyKey;
        this.subjectType = subjectType;
        this.projectKey = projectKey;
        this.content = content;
        this.observedAt = observedAt;
        this.sourceClient = sourceClient;
        this.sourceConversationId = sourceConversationId;
        this.sourceMessageId = sourceMessageId;
        this.inputSchemaVersion = inputSchemaVersion;
    }

    static RememberToolInput from(Map<String, Object> arguments) {
        Map<String, Object> values = requiredArguments(arguments);
        requireKnownFields(
                values,
                Set.of(
                        "idempotency_key",
                        "subject",
                        "content",
                        "observed_at",
                        "source",
                        "input_schema_version"));
        Map<String, Object> subject = requiredMap(values, "subject");
        requireKnownFields(subject, Set.of("type", "key"));
        String type = requiredString(subject, "type");
        SubjectType subjectType;
        try {
            subjectType = SubjectType.valueOf(type);
        } catch (IllegalArgumentException failure) {
            throw invalidInput();
        }

        String projectKey = optionalString(subject, "key");
        if (subjectType == SubjectType.USER && subject.containsKey("key")) {
            throw invalidInput();
        }
        if (subjectType == SubjectType.PROJECT && projectKey == null) {
            throw invalidInput();
        }

        Map<String, Object> source = requiredMap(values, "source");
        requireKnownFields(source, Set.of("client", "conversation_id", "message_id"));
        return new RememberToolInput(
                requiredString(values, "idempotency_key"),
                subjectType,
                projectKey,
                requiredString(values, "content"),
                parseObservedAt(requiredString(values, "observed_at")),
                requiredString(source, "client"),
                requiredString(source, "conversation_id"),
                requiredString(source, "message_id"),
                schemaVersion(values));
    }

    RememberCommand toCommand() {
        return new RememberCommand(
                idempotencyKey,
                subjectType,
                projectKey,
                content,
                observedAt.toInstant(),
                sourceClient,
                sourceConversationId,
                sourceMessageId,
                inputSchemaVersion);
    }

    @Override
    public String toString() {
        return "RememberToolInput[redacted]";
    }

    private static OffsetDateTime parseObservedAt(String value) {
        Matcher matcher = RFC_3339_OFFSET.matcher(value);
        if (!matcher.matches()) {
            throw invalidInput();
        }
        String fraction = matcher.group(2);
        String normalized = value;
        if (fraction != null && fraction.length() > 6) {
            if (!fraction.substring(6).chars().allMatch(character -> character == '0')) {
                throw invalidInput();
            }
            normalized = matcher.group(1) + "." + fraction.substring(0, 6) + matcher.group(3);
        }
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException failure) {
            throw invalidInput();
        }
    }

    private static int schemaVersion(Map<String, Object> values) {
        Object version = values.get("input_schema_version");
        if (!(version instanceof Integer integer) || integer != 1) {
            throw invalidInput();
        }
        return integer;
    }

    static Map<String, Object> requiredArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            throw invalidInput();
        }
        return arguments;
    }

    static Map<String, Object> requiredMap(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidInput();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalidInput();
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static void requireKnownFields(Map<String, Object> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet())) {
            throw invalidInput();
        }
    }

    static String requiredString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof String text)) {
            throw invalidInput();
        }
        return text;
    }

    static String optionalString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            if (values.containsKey(field)) {
                throw invalidInput();
            }
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalidInput();
        }
        return text;
    }

    static MemoryException invalidInput() {
        return new MemoryException(MemoryErrorCode.INVALID_ARGUMENT, "invalid input");
    }
}
