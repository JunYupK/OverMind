package com.overmind.config;

import static org.assertj.core.api.Assertions.assertThat;
import static com.overmind.support.SignedJwtFixture.*;

import com.nimbusds.jose.JOSEException;
import com.overmind.support.PostgresTestBase;
import com.overmind.support.SignedJwtFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** L2. Signed bearer tokens pass through the real decoder, validators, security chain, and MCP. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=production")
@Import(McpAuthorizationTest.SignedFixtureJwt.class)
class McpAuthorizationTest extends PostgresTestBase {
    private static final String SCHEMA = "t12_auth_" + UUID.randomUUID().toString().replace("-", "");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("overmind.security.issuer", () -> ISSUER);
        registry.add("overmind.security.audience", () -> "overmind");
        registry.add("overmind.security.allowed-subject", () -> SUBJECT);
    }

    @AfterEach
    void cleanOnlyThisSchema() {
        http.close();
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("DELETE FROM observation");
        jdbc.update("DELETE FROM memory_subject");
    }

    @Test
    void an_unauthenticated_call_is_rejected() throws Exception {
        HttpResponse<String> result = post("/mcp", null, null, recall());
        assertError(result, 401, "UNAUTHENTICATED");
        assertThat(result.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "issuer", "missing-issuer", "audience", "missing-audience", "expired", "not-before",
            "subject", "missing-subject", "missing-expiry", "malformed", "unsigned"})
    void invalid_tokens_are_rejected_by_the_actual_decoder(String defect) throws Exception {
        String token = token("memory:read memory:write", defect);
        HttpResponse<String> result = post("/mcp", token, null, recall());
        assertError(result, 401, "UNAUTHENTICATED");
        assertThat(result.body()).doesNotContain(token, MARKER, "Jwt", "Exception");
        assertNoWrites();
    }

    @Test
    void read_scope_alone_cannot_remember() throws Exception {
        String bearer = token("memory:read", null);
        try (Session session = initialize(bearer)) {
            assertError(post("/mcp", bearer, session, remember()), 403, "PERMISSION_DENIED");
            assertNoWrites();
            assertThat(toolResult(post("/mcp", bearer, session, recall())).path("isError").asBoolean()).isFalse();
        }
    }

    @Test
    void write_scope_alone_cannot_recall() throws Exception {
        String bearer = token("memory:write", null);
        try (Session session = initialize(bearer)) {
            assertError(post("/mcp", bearer, session, recall()), 403, "PERMISSION_DENIED");
            assertNoWrites();
            JsonNode result = toolResult(post("/mcp", bearer, session, remember()));
            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM observation", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void missing_scopes_cannot_call_either_tool_or_disguise_a_write_as_a_notification() throws Exception {
        String bearer = token(null, null);
        try (Session session = initialize(bearer)) {
            assertError(post("/mcp", bearer, session, remember()), 403, "PERMISSION_DENIED");
            assertError(post("/mcp", bearer, session, recall()), 403, "PERMISSION_DENIED");
            assertError(post("/mcp", bearer, session, remember().replaceAll("\"id\":2,|,\"id\":2", "")),
                    403, "PERMISSION_DENIED");
            assertNoWrites();
        }
    }

    @Test
    void both_scopes_store_and_recall_through_the_authenticated_mcp_session() throws Exception {
        String bearer = token("memory:read memory:write", null);
        try (Session session = initialize(bearer)) {
            JsonNode stored = toolResult(post("/mcp", bearer, session, remember()));
            assertThat(stored.path("isError").asBoolean()).isFalse();
            JsonNode recalled = toolResult(post("/mcp", bearer, session, recall()));
            assertThat(recalled.path("isError").asBoolean()).isFalse();
            assertThat(recalled.path("structuredContent").path("observations").size()).isEqualTo(1);
        }
    }

    @Test
    void authentication_and_scope_are_rechecked_on_every_request_in_a_session() throws Exception {
        String both = token("memory:read memory:write", null);
        try (Session session = initialize(both)) {
            assertError(post("/mcp", null, session, remember()), 401, "UNAUTHENTICATED");
            assertError(post("/mcp", token("memory:read", null), session, remember()), 403, "PERMISSION_DENIED");
            assertError(post("/mcp", token("memory:read memory:write", "subject"), session, recall()),
                    401, "UNAUTHENTICATED");
            assertNoWrites();
        }
    }

    @Test
    void query_form_and_static_tokens_are_not_authentication_fallbacks() throws Exception {
        String bearer = token("memory:read memory:write", null);
        assertError(post("/mcp?access_token=" + bearer, null, null, recall()), 401, "UNAUTHENTICATED");
        HttpResponse<String> form = http.send(HttpRequest.newBuilder(endpoint("/mcp"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("access_token=" + bearer)).build(), HttpResponse.BodyHandlers.ofString());
        assertError(form, 401, "UNAUTHENTICATED");
        assertError(post("/mcp", MARKER, null, recall()), 401, "UNAUTHENTICATED");
    }

    @Test
    void only_the_mcp_endpoint_is_exposed() throws Exception {
        String bearer = token("memory:read memory:write", null);
        for (String path : List.of("/", "/remember", "/recall", "/actuator/health", "/mcp/other")) {
            assertError(post(path, bearer, null, "{}"), 403, "PERMISSION_DENIED");
        }
        for (String method : List.of("GET", "DELETE")) {
            HttpResponse<String> result = http.send(HttpRequest.newBuilder(endpoint("/mcp"))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertError(result, 401, "UNAUTHENTICATED");
        }
    }

    private void assertNoWrites() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_subject", Integer.class)).isZero();
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(MARKER, "stackTrace", "Exception");
    }

    private Session initialize(String bearer) throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"auth-test\",\"version\":\"1\"}}}";
        HttpResponse<String> initialized = post("/mcp", bearer, null, request);
        assertThat(initialized.statusCode()).isEqualTo(200);
        Session session = new Session(initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(),
                rpc(initialized).path("result").path("protocolVersion").asString(), bearer);
        assertThat(post("/mcp", bearer, session,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode()).isEqualTo(202);
        return session;
    }

    private HttpResponse<String> post(String path, String bearer, Session session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (session != null) request.header("Mcp-Session-Id", session.id).header("MCP-Protocol-Version", session.protocol);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI endpoint(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private static JsonNode rpc(HttpResponse<String> response) {
        String json = response.body().stripLeading().startsWith("{") ? response.body()
                : response.body().lines().filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5).stripLeading()).findFirst().orElseThrow();
        return JSON.readTree(json);
    }

    private static JsonNode toolResult(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode rpc = rpc(response);
        assertThat(rpc.has("error")).isFalse();
        assertThat(rpc.has("result")).isTrue();
        return rpc.path("result");
    }

    private static String recall() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"recall_memory\",\"arguments\":{}}}";
    }

    private static String remember() {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", Map.of(
                "name", "remember_memory", "arguments", Map.of("idempotency_key", "t12-" + UUID.randomUUID(),
                        "subject", Map.of("type", "USER"), "content", MARKER, "observed_at", "2026-09-02T10:00:00Z",
                        "source", Map.of("client", "test-client", "conversation_id", MARKER, "message_id", MARKER),
                        "input_schema_version", 1))));
    }

    private final class Session implements AutoCloseable {
        final String id;
        final String protocol;
        final String bearer;
        Session(String id, String protocol, String bearer) { this.id = id; this.protocol = protocol; this.bearer = bearer; }
        @Override public void close() throws Exception {
            http.send(HttpRequest.newBuilder(endpoint("/mcp")).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearer).header("Mcp-Session-Id", id)
                    .header("MCP-Protocol-Version", protocol).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SignedFixtureJwt {
        @Bean @Primary
        JwtDecoder fixtureJwtDecoder(RequiredSettings settings) throws JOSEException {
            return SignedJwtFixture.decoder(settings);
        }
    }
}
