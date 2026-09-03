package com.overmind.application.memory;

/**
 * Spec §5.2 recall input. Every field is optional.
 *
 * @param projectKey absent means USER observations only; present merges USER with that PROJECT
 * @param limit absent means {@link RecallMemory#DEFAULT_LIMIT}
 * @param cursor absent means the first page
 */
public record RecallQuery(String projectKey, Integer limit, String cursor) {}
