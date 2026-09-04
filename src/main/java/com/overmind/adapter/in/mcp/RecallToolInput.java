package com.overmind.adapter.in.mcp;

import com.overmind.application.memory.RecallQuery;
import java.util.Map;
import java.util.Set;

/** Raw MCP arguments for {@code recall_memory}, converted without numeric or string coercion. */
final class RecallToolInput {

    private final String projectKey;
    private final Integer limit;
    private final String cursor;

    private RecallToolInput(String projectKey, Integer limit, String cursor) {
        this.projectKey = projectKey;
        this.limit = limit;
        this.cursor = cursor;
    }

    static RecallToolInput from(Map<String, Object> arguments) {
        Map<String, Object> values = RememberToolInput.requiredArguments(arguments);
        RememberToolInput.requireKnownFields(values, Set.of("project_key", "limit", "cursor"));
        return new RecallToolInput(
                RememberToolInput.optionalString(values, "project_key"),
                optionalInteger(values, "limit"),
                RememberToolInput.optionalString(values, "cursor"));
    }

    RecallQuery toQuery() {
        return new RecallQuery(projectKey, limit, cursor);
    }

    @Override
    public String toString() {
        return "RecallToolInput[redacted]";
    }

    private static Integer optionalInteger(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            if (values.containsKey(field)) {
                throw RememberToolInput.invalidInput();
            }
            return null;
        }
        if (!(value instanceof Integer integer)) {
            throw RememberToolInput.invalidInput();
        }
        return integer;
    }
}
