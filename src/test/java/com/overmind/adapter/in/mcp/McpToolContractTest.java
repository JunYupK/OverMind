package com.overmind.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.support.PostgresTestBase;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** L2. Spec §5·§9: M0 exposes exactly its two public memory tools. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolContractTest extends PostgresTestBase {

    @Autowired private ApplicationContext context;

    @Test
    void exactly_two_tools_are_exposed() {
        List<String> toolNames = McpToolNames.discover(context);

        assertThat(toolNames)
                .as("M0 exposes remember_memory and recall_memory only; there is no delete tool")
                .containsExactlyInAnyOrder("remember_memory", "recall_memory");
    }
}
