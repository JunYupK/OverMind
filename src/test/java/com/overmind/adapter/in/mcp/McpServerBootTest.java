package com.overmind.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.support.PostgresTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** L2. MCP 서버 자동설정이 실제로 Streamable HTTP transport를 기동하는지 확인한다. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerBootTest extends PostgresTestBase {

    @Autowired private ApplicationContext context;

    @Test
    void the_streamable_http_transport_is_active() {
        assertThat(
                        context.getBeanNamesForType(
                                org.springframework.ai.mcp.server.webmvc.transport
                                        .WebMvcStreamableServerTransportProvider.class))
                .as("Streamable HTTP transport 빈이 없습니다. 프로토콜 설정을 확인하세요")
                .isNotEmpty();
    }

    @Test
    void the_legacy_sse_transport_is_not_active() {
        assertThat(
                        context.getBeanNamesForType(
                                org.springframework.ai.mcp.server.webmvc.transport
                                        .WebMvcSseServerTransportProvider.class))
                .as("스펙 §6은 legacy SSE 전용 transport를 제공하지 않는다고 못 박았습니다")
                .isEmpty();
    }
}
