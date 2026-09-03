package com.overmind.application.memory;

import com.overmind.domain.memory.Observation;
import java.util.List;

/** A keyset page. Task 8 supplies its query implementation. */
public record RecallPage(List<Observation> items, boolean hasMore) {}
