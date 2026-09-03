package com.overmind.application.memory;

import java.util.List;

/**
 * Spec §5.2 recall output.
 *
 * @param nextCursor null when this page is the last one
 */
public record RecallResult(List<RecallItem> observations, String nextCursor) {}
