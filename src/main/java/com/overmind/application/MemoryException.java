package com.overmind.application;

/** An expected application error with a stable code for transport adapters. */
public class MemoryException extends RuntimeException {

    private final MemoryErrorCode code;

    public MemoryException(MemoryErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public MemoryErrorCode code() {
        return code;
    }
}
