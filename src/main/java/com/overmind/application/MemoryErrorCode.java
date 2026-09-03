package com.overmind.application;

/** Stable, input-safe error categories exposed by memory use cases. */
public enum MemoryErrorCode {
    INVALID_ARGUMENT,
    SUBJECT_NOT_FOUND,
    IDEMPOTENCY_CONFLICT,
    INVALID_CURSOR,
    UNAUTHENTICATED,
    PERMISSION_DENIED,
    INTERNAL_ERROR
}
