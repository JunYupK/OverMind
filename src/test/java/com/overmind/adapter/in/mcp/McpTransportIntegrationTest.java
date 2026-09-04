package com.overmind.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.overmind.OvermindApplication;
import com.overmind.application.memory.RememberMemory;
import com.overmind.support.PostgresTestBase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** L2. Real Streamable HTTP calls verify the public tool contract through PostgreSQL. */
@Tag("integration")
@SpringBootTest(
        classes = OvermindApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(McpTransportIntegrationTest.ProtocolTestSecurity.class)
class McpTransportIntegrationTest extends PostgresTestBase {
    private static final String SCHEMA = "t11_wire_" + UUID.randomUUID().toString().replace("-", "");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PRIVATE_MARKER = "T11_PRIVATE_PAYLOAD";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RememberMemory remember;
    private McpSyncClient client;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
    }

    @BeforeEach
    void connect() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        client = McpClient.sync(
                        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                                .endpoint("/mcp")
                                .build())
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .enableCallToolSchemaCaching(false)
                .build();
        client.initialize();
    }

    @AfterEach
    void disconnectAndDeleteTestRows() {
        if (client != null) {
            client.closeGracefully();
        }
        // This schema belongs only to this class and contains no shared or production rows.
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("DELETE FROM observation");
        jdbc.update("DELETE FROM memory_subject");
    }

    @Test
    void server_advertises_exactly_the_two_public_tools_with_unwrapped_schemas() {
        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name)
                .containsExactlyInAnyOrder("remember_memory", "recall_memory");
        JsonNode rememberSchema = JSON.valueToTree(tool(tools, "remember_memory").inputSchema());
        assertThat(rememberSchema.path("properties").propertyNames())
                .containsExactlyInAnyOrder(
                        "idempotency_key", "subject", "content", "observed_at", "source",
                        "input_schema_version");
        assertThat(rememberSchema.path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder(
                        "idempotency_key", "subject", "content", "observed_at", "source",
                        "input_schema_version");
        JsonNode recallSchema = JSON.valueToTree(tool(tools, "recall_memory").inputSchema());
        assertThat(recallSchema.path("properties").propertyNames())
                .containsExactlyInAnyOrder("project_key", "limit", "cursor");
        assertThat(recallSchema.path("required").size()).isZero();
    }

    @Test
    void remember_is_durable_idempotent_and_recall_pages_expose_only_public_fields() {
        Map<String, Object> userRequest = observation("USER", null, "2026-09-02t09:00:00.123456z");
        JsonNode user = success("remember_memory", userRequest);
        assertThat(user.propertyNames()).containsExactlyInAnyOrder("status", "observation_id", "created");
        assertThat(user.path("status").asString()).isEqualTo("STORED");
        assertThat(user.path("created").asBoolean()).isTrue();
        String userId = user.path("observation_id").asString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation WHERE id = ?", Integer.class,
                UUID.fromString(userId))).isEqualTo(1);
        JsonNode retry = success("remember_memory", userRequest);
        assertThat(retry.path("observation_id").asString()).isEqualTo(userId);
        assertThat(retry.path("created").asBoolean()).isFalse();

        String project = "wire-project";
        Map<String, Object> projectRequest = observation("PROJECT", project, "2026-09-02T18:01:00.1234560000+09:00");
        String projectId = success("remember_memory", projectRequest).path("observation_id").asString();
        JsonNode first = success("recall_memory", Map.of("project_key", project, "limit", 1));
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("observations", "next_cursor");
        assertThat(first.path("observations").size()).isEqualTo(1);
        JsonNode projectItem = first.path("observations").get(0);
        assertItem(projectItem, projectId, "PROJECT", "2026-09-02T09:01:00.123456Z");
        assertThat(projectItem.path("subject").propertyNames()).containsExactlyInAnyOrder("type", "key");
        assertThat(projectItem.path("subject").path("key").asString()).isEqualTo(project);

        JsonNode last = success("recall_memory", Map.of(
                "project_key", project, "limit", 1, "cursor", first.path("next_cursor").asString()));
        assertThat(last.propertyNames()).containsExactly("observations");
        assertThat(last.path("observations").size()).isEqualTo(1);
        JsonNode userItem = last.path("observations").get(0);
        assertItem(userItem, userId, "USER", "2026-09-02T09:00:00.123456Z");
        assertThat(userItem.path("subject").propertyNames()).containsExactly("type");
        JsonNode userOnly = success("recall_memory", Map.of());
        assertThat(userOnly.path("observations").size()).isEqualTo(1);
        assertThat(userOnly.path("observations").get(0).path("observation_id").asString()).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation", Integer.class)).isEqualTo(2);

        userRequest.put("content", PRIVATE_MARKER + " changed");
        error("remember_memory", userRequest, "IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation", Integer.class)).isEqualTo(2);
    }

    @Test
    void recall_errors_keep_the_stable_code_and_do_not_echo_private_arguments() {
        error("recall_memory", Map.of("project_key", "t11-private-missing-project"), "SUBJECT_NOT_FOUND");
        error("recall_memory", Map.of("cursor", PRIVATE_MARKER), "INVALID_CURSOR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_subject", Integer.class)).isZero();
    }

    @ParameterizedTest
    @MethodSource("malformedCalls")
    void malformed_arguments_are_tool_errors_without_echoing_input(String name, Map<String, Object> input) {
        error(name, input, "INVALID_ARGUMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_subject", Integer.class)).isZero();
    }

    static Stream<Arguments> malformedCalls() {
        Map<String, Object> base = observation("USER", null, "2026-09-02T09:00:00Z");
        return Stream.of(
                Arguments.of("remember_memory", Map.of()),
                Arguments.of("remember_memory", changed(base, "input_schema_version", 2)),
                Arguments.of("remember_memory", changed(base, "input_schema_version", 1.5)),
                Arguments.of("remember_memory", changed(base, "input_schema_version", "1")),
                Arguments.of("remember_memory", changed(base, "observed_at", "2026-09-02T09:00:00")),
                Arguments.of("remember_memory", changed(base, "observed_at", "2026-09-02T09:00Z")),
                Arguments.of("remember_memory", changed(base, "observed_at", "2026-09-02T09:00:00+09:00:30")),
                Arguments.of("remember_memory", changed(base, "observed_at", "2026-09-02T09:00:00.1234567Z")),
                Arguments.of("remember_memory", changed(base, "subject", Map.of("type", "USER", "key", PRIVATE_MARKER))),
                Arguments.of("remember_memory", changed(base, "subject", changed(Map.of("type", "USER"), "key", null))),
                Arguments.of("remember_memory", changed(base, "subject", Map.of("type", PRIVATE_MARKER))),
                Arguments.of("remember_memory", changed(base, "subject", Map.of("type", "PROJECT"))),
                Arguments.of("remember_memory", changed(base, "source", PRIVATE_MARKER)),
                Arguments.of("remember_memory", changed(base, "identity", PRIVATE_MARKER)),
                Arguments.of("remember_memory", changed(base, "source", Map.of(
                        "client", "wire-test", "conversation_id", "c", "message_id", "m", "token", PRIVATE_MARKER))),
                Arguments.of("recall_memory", Map.of("limit", 0)),
                Arguments.of("recall_memory", Map.of("limit", 101)),
                Arguments.of("recall_memory", Map.of("limit", 1.5)),
                Arguments.of("recall_memory", Map.of("limit", "1")),
                Arguments.of("recall_memory", changed(Map.of(), "limit", null)),
                Arguments.of("recall_memory", changed(Map.of(), "project_key", null)),
                Arguments.of("recall_memory", changed(Map.of(), "cursor", null)),
                Arguments.of("recall_memory", changed(Map.of(), "identity", null)),
                Arguments.of("recall_memory", Map.of("cursor", List.of(PRIVATE_MARKER))));
    }

    @Test
    void unexpected_failure_is_a_sanitized_internal_tool_error() {
        doThrow(new IllegalStateException("DB_EXCEPTION_" + PRIVATE_MARKER))
                .when(remember).handle(any());
        JsonNode result = error("remember_memory",
                observation("USER", null, "2026-09-02T09:00:00Z"), "INTERNAL_ERROR");
        assertThat(result.toString()).doesNotContain("DB_EXCEPTION", "IllegalStateException", "stackTrace");
    }

    private static Tool tool(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Object> observation(String type, String project, String observedAt) {
        Map<String, Object> subject = project == null ? Map.of("type", type) : Map.of("type", type, "key", project);
        return new LinkedHashMap<>(Map.of(
                "idempotency_key", "t11-" + UUID.randomUUID(),
                "subject", subject,
                "content", "기억 " + PRIVATE_MARKER,
                "observed_at", observedAt,
                "source", Map.of("client", "wire-test", "conversation_id", PRIVATE_MARKER + "-conversation",
                        "message_id", PRIVATE_MARKER + "-message"),
                "input_schema_version", 1));
    }

    private static Map<String, Object> changed(Map<String, Object> base, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        result.put(key, value);
        return result;
    }

    private static JsonNode payload(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
        JsonNode text = JSON.readTree(((TextContent) result.content().get(0)).text());
        if (result.structuredContent() != null) {
            JsonNode structured = JSON.valueToTree(result.structuredContent());
            assertThat(structured).isEqualTo(text);
        }
        return text;
    }

    private JsonNode success(String name, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(new CallToolRequest(name, arguments));
        assertThat(result.isError()).isNotEqualTo(true);
        return payload(result);
    }

    private JsonNode error(String name, Map<String, Object> arguments, String expectedCode) {
        CallToolResult result = arguments.values().stream().anyMatch(value -> value == null)
                ? callWithLiteralNull(arguments.keySet().iterator().next())
                : client.callTool(new CallToolRequest(name, arguments));
        assertThat(result.isError()).isTrue();
        JsonNode body = payload(result);
        assertThat(body.path("code").asString()).isEqualTo(expectedCode);
        assertThat(body.toString()).doesNotContain(PRIVATE_MARKER, "t11-private-missing-project", "stackTrace");
        return body;
    }

    /** Send literal JSON to verify that server-side conversion preserves explicit null entries. */
    private CallToolResult callWithLiteralNull(String field) {
        String protocol = client.getCurrentInitializationResult().protocolVersion();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
        try (HttpClient http = HttpClient.newHttpClient()) {
            String initialize = JSON.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                            "protocolVersion", protocol, "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "null-contract-test", "version", "1"))));
            HttpResponse<String> initialized = http.send(
                    rpcRequest(endpoint, initialize).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(initialized.statusCode()).isEqualTo(200);
            String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            try {
                HttpResponse<String> ready = http.send(
                        rpcRequest(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                                .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", protocol).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(ready.statusCode()).isEqualTo(202);
                String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"recall_memory\",\"arguments\":{" + JSON.writeValueAsString(field) + ":null}}}";
                HttpResponse<String> response = http.send(
                        rpcRequest(endpoint, body).header("Mcp-Session-Id", session)
                                .header("MCP-Protocol-Version", protocol).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                String json = response.body().stripLeading().startsWith("{") ? response.body()
                        : response.body().lines().filter(line -> line.startsWith("data:"))
                                .map(line -> line.substring(5).stripLeading()).findFirst().orElseThrow();
                JsonNode rpc = JSON.readTree(json);
                assertThat(rpc.path("id").asInt()).isEqualTo(2);
                assertThat(rpc.has("error")).isFalse();
                return JSON.treeToValue(rpc.path("result"), CallToolResult.class);
            } finally {
                http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                                .header("Mcp-Session-Id", session).header("MCP-Protocol-Version", protocol)
                                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception failure) {
            throw new AssertionError("Could not complete literal-null MCP request", failure);
        }
    }

    private static HttpRequest.Builder rpcRequest(URI endpoint, String json) {
        return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private static void assertItem(JsonNode item, String id, String type, String observedAt) {
        assertThat(item.propertyNames()).containsExactlyInAnyOrder(
                "observation_id", "subject", "content", "client", "observed_at");
        assertThat(item.path("observation_id").asString()).isEqualTo(id);
        assertThat(item.path("subject").path("type").asString()).isEqualTo(type);
        assertThat(item.path("content").asString()).isEqualTo("기억 " + PRIVATE_MARKER);
        assertThat(item.path("client").asString()).isEqualTo("wire-test");
        assertThat(item.path("observed_at").asString()).isEqualTo(observedAt);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtocolTestSecurity {
        /** T11 isolates the MCP contract; real issuer/scope enforcement is tested in T12. */
        @Bean
        @Order(0)
        SecurityFilterChain protocolTestChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/mcp", "/mcp/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }
}
