package com.overmind.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.domain.DomainValidationException;
import org.junit.jupiter.api.Test;

class McpErrorMapperTest {

    @Test
    void preserves_the_code_of_an_expected_memory_error() {
        assertThat(
                        McpErrorMapper.toErrorCode(
                                new MemoryException(
                                        MemoryErrorCode.INVALID_CURSOR, "cursor is not valid")))
                .isEqualTo(MemoryErrorCode.INVALID_CURSOR);
    }

    @Test
    void maps_domain_validation_to_invalid_argument() {
        assertThat(McpErrorMapper.toErrorCode(new DomainValidationException("content is required")))
                .isEqualTo(MemoryErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void maps_unexpected_failures_to_internal_error() {
        assertThat(McpErrorMapper.toErrorCode(new IllegalStateException("secret content")))
                .isEqualTo(MemoryErrorCode.INTERNAL_ERROR);
    }
}
