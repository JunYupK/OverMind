package com.overmind.application.memory;

import java.util.UUID;

/** The observation selected by a remember call and whether this call created it. */
public record RememberResult(UUID observationId, boolean created) {}
