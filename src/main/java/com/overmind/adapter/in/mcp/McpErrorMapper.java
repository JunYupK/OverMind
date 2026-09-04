package com.overmind.adapter.in.mcp;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.domain.DomainValidationException;

/** Converts exceptions at the MCP boundary to the stable public error categories. */
final class McpErrorMapper {

    private McpErrorMapper() {}

    static MemoryErrorCode toErrorCode(Throwable failure) {
        if (failure instanceof MemoryException memoryException) {
            return memoryException.code();
        }
        if (failure instanceof DomainValidationException) {
            return MemoryErrorCode.INVALID_ARGUMENT;
        }
        return MemoryErrorCode.INTERNAL_ERROR;
    }

    static String toSafeMessage(Throwable failure) {
        if (failure instanceof MemoryException || failure instanceof DomainValidationException) {
            return failure.getMessage();
        }
        return "an internal error occurred";
    }
}
