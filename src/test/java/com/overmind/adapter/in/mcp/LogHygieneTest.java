package com.overmind.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.nimbusds.jose.JOSEException;
import com.overmind.config.RequiredSettings;
import com.overmind.support.LogCapture;
import com.overmind.support.PostgresTestBase;
import com.overmind.support.SignedJwtFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

/** INV-02: real HTTP, production security, signed JWT validation, MCP dispatch, and PostgreSQL. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=production")
@Import(LogHygieneTest.SignedFixtureJwt.class)
class LogHygieneTest extends PostgresTestBase {
    private static final String SCHEMA = "t13_logs_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ENDPOINT = "/mcp";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONTENT = "T13_PRIVATE_CONTENT_7a81";
    private static final String OTHER_CONTENT = "T13_PRIVATE_OTHER_CONTENT_89bc";
    private static final String IDEMPOTENCY = "T13_PRIVATE_IDEMPOTENCY_c278";
    private static final String PROJECT = "t13-private-project-d739";
    private static final String MISSING_PROJECT = "t13-private-missing-project-e841";
    private static final String CLIENT = "T13_PRIVATE_CLIENT_4f19";
    private static final String CONVERSATION = "T13_PRIVATE_CONVERSATION_b940";
    private static final String MESSAGE = "T13_PRIVATE_MESSAGE_c071";
    private static final String CURSOR = "T13_PRIVATE_INVALID_CURSOR_f314";
    private static final String ISSUER = SignedJwtFixture.ISSUER;
    private static final String AUDIENCE = "overmind";
    private static final String SUBJECT = SignedJwtFixture.SUBJECT;
    private static final String INVALID_SUBJECT = "T13_PRIVATE_INVALID_SUBJECT_f986";
    private static final String CLAIM = "T13_PRIVATE_CUSTOM_CLAIM_a419";
    private static final String SCOPE_MARKER = "t13:private-scope-b801";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> forbidden = new ArrayList<>(List.of(CONTENT, OTHER_CONTENT, IDEMPOTENCY,
            PROJECT, MISSING_PROJECT, CLIENT, CONVERSATION, MESSAGE, CURSOR, ISSUER,
            SUBJECT, INVALID_SUBJECT, CLAIM, SCOPE_MARKER));

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("overmind.security.issuer", () -> ISSUER);
        registry.add("overmind.security.audience", () -> AUDIENCE);
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
    void successful_store_and_paginated_recall_do_not_log_inputs_results_or_cursor() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            try (Session session = initialize(bearer("memory:read memory:write", SUBJECT))) {
                JsonNode stored = success(session.call("remember_memory", observation(IDEMPOTENCY, CONTENT)));
                assertThat(stored.path("status").asString()).isEqualTo("STORED");
                assertThat(stored.path("created").asBoolean()).isTrue();
                success(session.call("remember_memory", observation(IDEMPOTENCY + "-second", OTHER_CONTENT)));
                JsonNode page = success(session.call("recall_memory", Map.of("project_key", PROJECT, "limit", 1)));
                assertThat(page.path("observations").size()).isEqualTo(1);
                String cursor = page.path("next_cursor").asString();
                assertThat(cursor).isNotBlank();
                forbidden.add(cursor);
                JsonNode next = success(session.call("recall_memory",
                        Map.of("project_key", PROJECT, "limit", 1, "cursor", cursor)));
                assertThat(next.path("observations").size()).isEqualTo(1);
                assertThat(next.has("next_cursor")).isFalse();
                List<JsonNode> observations = List.of(page.path("observations").get(0), next.path("observations").get(0));
                assertThat(observations).extracting(item -> item.path("content").asString())
                        .containsExactlyInAnyOrder(CONTENT, OTHER_CONTENT);
                for (JsonNode item : observations) {
                    assertThat(item.path("subject").path("key").asString()).isEqualTo(PROJECT);
                    assertThat(item.path("client").asString()).isEqualTo(CLIENT);
                    assertThat(item.has("source")).isFalse();
                }
                assertRows(2);
            }
            assertClean(capture);
        }
    }

    @Test
    void validation_failure_does_not_log_rejected_payload() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            try (Session session = initialize(bearer("memory:read memory:write", SUBJECT))) {
                Map<String, Object> invalid = observation(IDEMPOTENCY, CONTENT);
                invalid.put("input_schema_version", 2);
                toolError(session.call("remember_memory", invalid), "INVALID_ARGUMENT");
                assertRows(0);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_subject", Integer.class)).isZero();
            }
            assertClean(capture);
        }
    }

    @Test
    void persisted_idempotency_conflict_does_not_log_either_payload_or_key() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            try (Session session = initialize(bearer("memory:read memory:write", SUBJECT))) {
                success(session.call("remember_memory", observation(IDEMPOTENCY, CONTENT)));
                toolError(session.call("remember_memory", observation(IDEMPOTENCY, OTHER_CONTENT)), "IDEMPOTENCY_CONFLICT");
                assertRows(1);
                assertThat(jdbc.queryForObject("SELECT content FROM observation", String.class)).isEqualTo(CONTENT);
            }
            assertClean(capture);
        }
    }

    @Test
    void invalid_cursor_does_not_log_cursor_or_exception_detail() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            try (Session session = initialize(bearer("memory:read", SUBJECT))) {
                toolError(session.call("recall_memory", Map.of("cursor", CURSOR)), "INVALID_CURSOR");
                assertRows(0);
            }
            assertClean(capture);
        }
    }

    @Test
    void missing_project_does_not_log_project_key() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            try (Session session = initialize(bearer("memory:read", SUBJECT))) {
                toolError(session.call("recall_memory", Map.of("project_key", MISSING_PROJECT)), "SUBJECT_NOT_FOUND");
                assertRows(0);
            }
            assertClean(capture);
        }
    }

    @Test
    void invalid_subject_is_401_without_logging_token_or_claims() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            String token = bearer("memory:read memory:write", INVALID_SUBJECT);
            HttpResponse<String> response = post(token, null, call("remember_memory", observation(IDEMPOTENCY, CONTENT)));
            httpError(response, 401, "UNAUTHENTICATED");
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            assertRows(0);
            assertClean(capture);
        }
    }

    @Test
    void insufficient_scope_is_403_without_logging_token_claims_or_payload() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            try (Session session = initialize(bearer("memory:read", SUBJECT))) {
                httpError(session.call("remember_memory", observation(IDEMPOTENCY, CONTENT)), 403, "PERMISSION_DENIED");
                assertRows(0);
                assertThat(success(session.call("recall_memory", Map.of())).path("observations").size()).isZero();
            }
            assertClean(capture);
        }
    }

    private void assertRows(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation", Integer.class)).isEqualTo(count);
    }

    private void assertClean(LogCapture capture) {
        // Drain the client/session lifecycle before inspecting the root appender; never narrow capture.
        http.close();
        assertThat(((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getLevel()).isEqualTo(Level.TRACE);
        capture.assertNoOccurrenceOf(forbidden.toArray(String[]::new));
    }

    private String bearer(String scope, String subject) throws JOSEException {
        String token = SignedJwtFixture.token(scope + " " + SCOPE_MARKER, null,
                Map.of("iss", ISSUER, "aud", List.of(AUDIENCE), "sub", subject, "private_claim", CLAIM));
        forbidden.add(token);
        return token;
    }

    private static Map<String, Object> observation(String key, String content) {
        return new LinkedHashMap<>(Map.of("idempotency_key", key, "subject", Map.of("type", "PROJECT", "key", PROJECT),
                "content", content, "observed_at", "2026-09-02T10:00:00Z", "input_schema_version", 1,
                "source", Map.of("client", CLIENT, "conversation_id", CONVERSATION, "message_id", MESSAGE)));
    }

    private static String call(String tool, Map<String, Object> arguments) {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
    }

    private static JsonNode success(HttpResponse<String> response) {
        JsonNode result = toolResult(response);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.has("structuredContent")).isTrue();
        return result.path("structuredContent");
    }

    private void toolError(HttpResponse<String> response, String expected) {
        JsonNode result = toolResult(response);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asString()).path("code").asString()).isEqualTo(expected);
        assertThat(response.body()).doesNotContain(forbidden.toArray(String[]::new));
    }

    private void httpError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(forbidden.toArray(String[]::new));
    }

    private static JsonNode toolResult(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode rpc = rpc(response);
        assertThat(rpc.has("error")).isFalse();
        assertThat(rpc.has("result")).isTrue();
        return rpc.path("result");
    }

    private static JsonNode rpc(HttpResponse<String> response) {
        String body = response.body().stripLeading();
        String json = body.startsWith("{") ? body : body.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).stripLeading()).findFirst().orElseThrow();
        return JSON.readTree(json);
    }

    private Session initialize(String bearer) throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"log-hygiene-test\",\"version\":\"1\"}}}";
        HttpResponse<String> initialized = post(bearer, null, request);
        assertThat(initialized.statusCode()).isEqualTo(200);
        Session session = new Session(initialized.headers().firstValue("Mcp-Session-Id").orElseThrow(),
                rpc(initialized).path("result").path("protocolVersion").asString(), bearer);
        assertThat(post(bearer, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode()).isEqualTo(202);
        return session;
    }

    private HttpResponse<String> post(String bearer, Session session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint()).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + bearer);
        if (session != null) request.header("Mcp-Session-Id", session.id).header("MCP-Protocol-Version", session.protocol);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI endpoint() { return URI.create("http://127.0.0.1:" + port + ENDPOINT); }

    private final class Session implements AutoCloseable {
        final String id;
        final String protocol;
        final String bearer;
        Session(String id, String protocol, String bearer) { this.id = id; this.protocol = protocol; this.bearer = bearer; }
        HttpResponse<String> call(String tool, Map<String, Object> arguments) throws Exception {
            return post(bearer, this, LogHygieneTest.call(tool, arguments));
        }
        @Override public void close() throws Exception {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(endpoint()).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearer).header("Mcp-Session-Id", id)
                    .header("MCP-Protocol-Version", protocol).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
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
