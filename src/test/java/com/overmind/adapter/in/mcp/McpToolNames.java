package com.overmind.adapter.in.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;

final class McpToolNames {

    private McpToolNames() {}

    static List<String> discover(ApplicationContext context) {
        ResolvableType specifications =
                ResolvableType.forClassWithGenerics(List.class, SyncToolSpecification.class);
        return context.getBeanProvider(specifications).orderedStream()
                .flatMap(value -> ((List<?>) value).stream())
                .map(SyncToolSpecification.class::cast)
                .map(specification -> specification.tool().name())
                .toList();
    }
}
