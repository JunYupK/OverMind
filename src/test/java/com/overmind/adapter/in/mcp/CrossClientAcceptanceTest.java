package com.overmind.adapter.in.mcp;

import static com.overmind.support.SignedJwtFixture.ISSUER;
import static com.overmind.support.SignedJwtFixture.SUBJECT;
import static com.overmind.support.SignedJwtFixture.token;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.overmind.OvermindApplication;
import com.overmind.config.RequiredSettings;
import com.overmind.support.PostgresTestBase;
import com.overmind.support.SignedJwtFixture;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L2. Spec §10 acceptance criteria 1–6 and 8.
 *
 * <p><b>Two independent MCP clients share one authenticated identity.</b> AC 1–4 are about
 * clients, not use cases, so this drives the real Streamable HTTP endpoint with signed bearer
 * tokens rather than autowiring {@code RememberMemory}/{@code RecallMemory} directly. Calling
 * the use cases would pass while the transport, the security chain, or the tool schemas were
 * broken — which is exactly what these criteria exist to rule out.
 *
 * <p>AC 7 (no update or delete on the persistence port) is a shape, not a runtime behaviour;
 * {@code ObservationPortShapeTest} pins it as L1. AC 9 (log hygiene) is {@code LogHygieneTest},
 * AC 10 (fail-closed) is {@code RequiredSettingsTest} and {@code McpAuthorizationTest}. The
 * mapping from each criterion to its evidence lives in {@code log.md}.
 */
@Tag("integration")
@SpringBootTest(
        classes = OvermindApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=production")
@Import(CrossClientAcceptanceTest.SignedFixtureJwt.class)
class CrossClientAcceptanceTest extends PostgresTestBase {

    private static final String SCHEMA = "t14_acc_" + UUID.randomUUID().toString().replace("-", "");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OBSERVED_EARLIER = "2026-09-02T09:00:00Z";
    private static final String OBSERVED_LATER = "2026-09-02T10:00:00Z";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    /** Two clients, one identity — the shape AC 1 describes. */
    private McpSyncClient clientA;
    private McpSyncClient clientB;

    @DynamicPropertySource
    static void isolatedDatabaseAndIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("overmind.security.issuer", () -> ISSUER);
        registry.add("overmind.security.audience", () -> "overmind");
        registry.add("overmind.security.allowed-subject", () -> SUBJECT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SignedFixtureJwt {

        @Bean
        @Primary
        JwtDecoder fixtureJwtDecoder(RequiredSettings settings) throws JOSEException {
            return SignedJwtFixture.decoder(settings);
        }
    }

    @BeforeEach
    void connectBothClients() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        clientA = connect();
        clientB = connect();
    }

    @AfterEach
    void disconnectAndCleanOnlyThisSchema() {
        closeQuietly(clientA);
        closeQuietly(clientB);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("DELETE FROM observation");
        jdbc.update("DELETE FROM memory_subject");
    }

    // ---------- AC 1, 2 ----------

    @Test
    void what_one_client_stores_another_client_recalls() {
        String marker = "cross-client-" + UUID.randomUUID();
        rememberUser(clientA, "client-a", marker, OBSERVED_LATER);

        JsonNode recalled = call(clientB, "recall_memory", Map.of("limit", 100));

        assertThat(contents(recalled)).contains(marker);
    }

    // ---------- AC 3, 4 ----------

    @Test
    void a_project_recall_returns_user_and_project_together_newest_first() {
        String project = "acc-" + UUID.randomUUID().toString().substring(0, 8);
        String userSide = "user side " + UUID.randomUUID();
        String projectSide = "project side " + UUID.randomUUID();
        rememberUser(clientA, "client-a", userSide, OBSERVED_EARLIER);
        rememberProject(clientA, "client-a", project, projectSide, OBSERVED_LATER);

        JsonNode recalled =
                call(clientB, "recall_memory", Map.of("project_key", project, "limit", 100));

        assertThat(contents(recalled))
                .as("spec §5.2: USER and the named PROJECT merge, newest first")
                .containsSubsequence(projectSide, userSide);
    }

    @Test
    void a_project_stored_by_one_client_is_reachable_by_key_from_another() {
        String project = "acc-" + UUID.randomUUID().toString().substring(0, 8);
        String marker = "project-only " + UUID.randomUUID();
        rememberProject(clientA, "client-a", project, marker, OBSERVED_LATER);

        JsonNode recalled =
                call(clientB, "recall_memory", Map.of("project_key", project, "limit", 100));

        assertThat(contents(recalled)).contains(marker);
    }

    // ---------- AC 5 ----------

    @Test
    void a_stored_observation_is_durable_in_postgresql_right_after_the_call_returns() {
        String marker = "durable-" + UUID.randomUUID();

        JsonNode stored = rememberUser(clientA, "client-a", marker, OBSERVED_LATER);

        assertThat(stored.path("status").asString()).isEqualTo("STORED");
        String observationId = stored.path("observation_id").asString();
        Integer rows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM observation WHERE id = ?::uuid",
                        Integer.class,
                        observationId);
        assertThat(rows)
                .as("spec §5.1: STORED means the row is already committed, not queued")
                .isEqualTo(1);
    }

    // ---------- AC 6 ----------

    @Test
    void a_retry_with_the_same_idempotency_key_does_not_duplicate() {
        String idempotencyKey = "acc-retry-" + UUID.randomUUID();
        Map<String, Object> arguments = userArguments("client-a", "retried fact", OBSERVED_LATER);
        arguments.put("idempotency_key", idempotencyKey);

        JsonNode first = call(clientA, "remember_memory", arguments);
        JsonNode second = call(clientB, "remember_memory", arguments);

        assertThat(first.path("created").asBoolean()).isTrue();
        assertThat(second.path("created").asBoolean()).isFalse();
        assertThat(second.path("observation_id").asString())
                .isEqualTo(first.path("observation_id").asString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM observation WHERE idempotency_key = ?",
                                Integer.class,
                                idempotencyKey))
                .isEqualTo(1);
    }

    // ---------- AC 8 ----------

    @Test
    void a_cursor_walks_every_observation_exactly_once_across_clients() {
        int total = 25;
        for (int i = 0; i < total; i++) {
            rememberUser(
                    clientA,
                    "client-a",
                    "paged " + i,
                    "2026-09-02T00:00:" + String.format("%02d", i) + "Z");
        }

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("limit", 10);
            if (cursor != null) {
                arguments.put("cursor", cursor);
            }
            // Alternating clients: the cursor is server-signed state, not client state.
            JsonNode result = call(page % 2 == 0 ? clientA : clientB, "recall_memory", arguments);
            result.path("observations").forEach(item -> walked.add(item.path("observation_id").asString()));
            JsonNode next = result.path("next_cursor");
            cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
            if (cursor == null) {
                break;
            }
        }

        assertThat(walked).doesNotHaveDuplicates().hasSize(total);
        assertThat(cursor).as("the final page must not offer a further cursor").isNull();
    }

    // ---------- helpers ----------

    private McpSyncClient connect() throws JOSEException {
        String bearer = token("memory:read memory:write", null);
        McpSyncClient client =
                McpClient.sync(
                                HttpClientStreamableHttpTransport.builder(
                                                "http://127.0.0.1:" + port)
                                        .endpoint("/mcp")
                                        .httpRequestCustomizer(
                                                (request, method, uri, body, context) ->
                                                        request.header(
                                                                "Authorization", "Bearer " + bearer))
                                        .build())
                        .requestTimeout(Duration.ofSeconds(10))
                        .initializationTimeout(Duration.ofSeconds(10))
                        .enableCallToolSchemaCaching(false)
                        .build();
        client.initialize();
        return client;
    }

    private static void closeQuietly(McpSyncClient client) {
        if (client != null) {
            client.closeGracefully();
        }
    }

    private JsonNode rememberUser(
            McpSyncClient client, String sourceClient, String content, String observedAt) {
        return call(client, "remember_memory", userArguments(sourceClient, content, observedAt));
    }

    private JsonNode rememberProject(
            McpSyncClient client,
            String sourceClient,
            String projectKey,
            String content,
            String observedAt) {
        Map<String, Object> arguments = userArguments(sourceClient, content, observedAt);
        arguments.put("subject", Map.of("type", "PROJECT", "key", projectKey));
        return call(client, "remember_memory", arguments);
    }

    private static Map<String, Object> userArguments(
            String sourceClient, String content, String observedAt) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("idempotency_key", "acc-" + UUID.randomUUID());
        arguments.put("subject", Map.of("type", "USER"));
        arguments.put("content", content);
        arguments.put("observed_at", observedAt);
        arguments.put(
                "source",
                Map.of(
                        "client", sourceClient,
                        "conversation_id", "conv-" + sourceClient,
                        "message_id", "msg-" + UUID.randomUUID()));
        arguments.put("input_schema_version", 1);
        return arguments;
    }

    private JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(new CallToolRequest(tool, arguments));
        assertThat(result.isError())
                .as("tool %s failed: %s", tool, result.content())
                .isNotEqualTo(true);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
        return JSON.readTree(((TextContent) result.content().get(0)).text());
    }

    private static List<String> contents(JsonNode recallResult) {
        List<String> contents = new ArrayList<>();
        recallResult.path("observations").forEach(item -> contents.add(item.path("content").asString()));
        return contents;
    }
}
