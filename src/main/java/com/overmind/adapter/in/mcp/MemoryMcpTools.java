package com.overmind.adapter.in.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.overmind.application.MemoryErrorCode;
import com.overmind.application.memory.RecallItem;
import com.overmind.application.memory.RecallMemory;
import com.overmind.application.memory.RecallResult;
import com.overmind.application.memory.RememberMemory;
import com.overmind.application.memory.RememberResult;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Inbound MCP adapter. It owns protocol schemas and converts only validated raw arguments. */
@Component
public class MemoryMcpTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RememberMemory rememberMemory;
    private final RecallMemory recallMemory;

    public MemoryMcpTools(RememberMemory rememberMemory, RecallMemory recallMemory) {
        this.rememberMemory = rememberMemory;
        this.recallMemory = recallMemory;
    }

    /**
     * Spring AI's default NON_NULL map-content inclusion drops explicit null arguments during
     * the SDK's convertValue step. Keep map entries so validation can distinguish null from an
     * omitted optional field. This named mapper is used only by the MCP server.
     */
    @Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
    JsonMapper mcpServerJsonMapper() {
        return JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .addModules(JacksonUtils.instantiateAvailableModules())
                .changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }

    /**
     * Direct specifications keep conversion inside this adapter. Spring AI's annotated callback
     * converts typed arguments before invoking user code and formats conversion errors with their
     * raw messages, which violates the MCP privacy contract for malformed requests.
     */
    @Bean
    List<SyncToolSpecification> memoryToolSpecifications() {
        return List.of(
                SyncToolSpecification.builder()
                        .tool(rememberTool())
                        .callHandler((exchange, request) -> remember(request))
                        .build(),
                SyncToolSpecification.builder()
                        .tool(recallTool())
                        .callHandler((exchange, request) -> recall(request))
                        .build());
    }

    /**
     * The advertised JSON schemas remain exact, while raw arguments reach this adapter for safe
     * error formatting. The SDK's built-in tool-input validator otherwise returns its own
     * plaintext messages before the handler can map malformed input to {@code INVALID_ARGUMENT}.
     */
    @Bean
    @Primary
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    McpSyncServerCustomizer rawMemoryToolInputCustomizer(
            @Qualifier("servletMcpSyncServerCustomizer") McpSyncServerCustomizer servletCustomizer) {
        return specification -> {
            servletCustomizer.customize(specification);
            specification.validateToolInputs(false);
        };
    }

    private CallToolResult remember(CallToolRequest request) {
        try {
            RememberResult result = rememberMemory.handle(RememberToolInput.from(request.arguments()).toCommand());
            return success(
                    Map.of(
                            "status", "STORED",
                            "observation_id", result.observationId().toString(),
                            "created", result.created()));
        } catch (Exception failure) {
            return error(failure);
        }
    }

    private CallToolResult recall(CallToolRequest request) {
        try {
            RecallResult result = recallMemory.handle(RecallToolInput.from(request.arguments()).toQuery());
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("observations", result.observations().stream().map(this::toResponse).toList());
            if (result.nextCursor() != null) {
                response.put("next_cursor", result.nextCursor());
            }
            return success(response);
        } catch (Exception failure) {
            return error(failure);
        }
    }

    private Map<String, Object> toResponse(RecallItem item) {
        LinkedHashMap<String, Object> subject = new LinkedHashMap<>();
        subject.put("type", item.subjectType().name());
        if (item.projectKey() != null) {
            subject.put("key", item.projectKey());
        }
        return Map.of(
                "observation_id", item.observationId().toString(),
                "subject", subject,
                "content", item.content(),
                "client", item.client(),
                "observed_at", item.observedAt().toString());
    }

    private static CallToolResult success(Map<String, Object> response) {
        return result(response, false);
    }

    private static CallToolResult error(Throwable failure) {
        MemoryErrorCode code = McpErrorMapper.toErrorCode(failure);
        return result(Map.of("code", code.name(), "message", McpErrorMapper.toSafeMessage(failure)), true);
    }

    private static CallToolResult result(Map<String, Object> response, boolean isError) {
        return CallToolResult.builder()
                .addTextContent(JSON.writeValueAsString(response))
                .structuredContent(response)
                .isError(isError)
                .build();
    }

    private static Tool rememberTool() {
        return Tool.builder()
                .name("remember_memory")
                .description("Store one raw memory observation.")
                .inputSchema(
                        objectSchema(
                                Map.of(
                                        "idempotency_key", Map.of("type", "string"),
                                        "subject", subjectSchema(),
                                        "content", Map.of("type", "string"),
                                        "observed_at", Map.of("type", "string", "format", "date-time"),
                                        "source", sourceSchema(),
                                        "input_schema_version", Map.of("type", "integer", "const", 1)),
                                List.of(
                                        "idempotency_key",
                                        "subject",
                                        "content",
                                        "observed_at",
                                        "source",
                                        "input_schema_version")))
                .build();
    }

    private static Tool recallTool() {
        return Tool.builder()
                .name("recall_memory")
                .description("Recall the current user's raw memory observations.")
                .inputSchema(
                        objectSchema(
                                Map.of(
                                        "project_key", Map.of("type", "string"),
                                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                                        "cursor", Map.of("type", "string")),
                                List.of()))
                .build();
    }

    private static Map<String, Object> subjectSchema() {
        return objectSchema(
                Map.of(
                        "type", Map.of("type", "string", "enum", List.of("USER", "PROJECT")),
                        "key", Map.of("type", "string")),
                List.of("type"));
    }

    private static Map<String, Object> sourceSchema() {
        return objectSchema(
                Map.of(
                        "client", Map.of("type", "string"),
                        "conversation_id", Map.of("type", "string"),
                        "message_id", Map.of("type", "string")),
                List.of("client", "conversation_id", "message_id"));
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
