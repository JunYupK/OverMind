package com.overmind.config;

import com.overmind.support.SignedJwtFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** L1. Real HTTP security filters with a counting terminal handler; no SDK/DB is substituted. */
class SecurityHttpBoundaryTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class, Fixture.class)
            .withPropertyValues(
                    "overmind.security.issuer=https://issuer.example.com",
                    "overmind.security.audience=overmind",
                    "overmind.security.allowed-subject=subject-1",
                    "overmind.security.cursor-secret=" + "test-cursor-key-".repeat(3));

    @Test
    void security_filters_authenticate_then_check_each_tool_before_dispatch_and_preserve_body() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var terminal = context.getBean(Terminal.class);
            String remember = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"remember_memory\",\"arguments\":{\"content\":\"preserved\"}}}";
            String recall = remember.replace("remember_memory", "recall_memory");
            assertThat(mvc.perform(post("/mcp").servletPath("/mcp").contentType(MediaType.APPLICATION_JSON)
                    .content(remember)).andReturn().getResponse().getStatus()).isEqualTo(401);
            for (String[] denied : new String[][] {
                    {"memory:read", remember}, {"memory:write", recall}, {"", remember}, {"", recall}}) {
                var response = mvc.perform(post("/mcp").servletPath("/mcp")
                        .header("Authorization", "Bearer " + SignedJwtFixture.token(denied[0], null))
                        .contentType(MediaType.APPLICATION_JSON).content(denied[1])).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(response.getContentAsString()).contains("PERMISSION_DENIED").doesNotContain("preserved");
            }
            assertThat(terminal.calls.get()).isZero();
            for (String[] allowed : new String[][] {{"memory:write", remember}, {"memory:read", recall}}) {
                var response = mvc.perform(post("/mcp").servletPath("/mcp")
                        .header("Authorization", "Bearer " + SignedJwtFixture.token(allowed[0], null))
                        .contentType(MediaType.APPLICATION_JSON).content(allowed[1])).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getContentAsString()).contains("preserved");
            }
            assertThat(terminal.calls.get()).isEqualTo(2);
        });
    }

    @Test
    void malformed_rpc_and_unknown_tools_cannot_reach_the_handler() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            String bearer = SignedJwtFixture.token("memory:read memory:write", null);
            for (String malformed : new String[] {"{", "null", "[]", "{\"method\":\"tools/call\",\"params\":{\"name\":null}}"}) {
                var response = mvc.perform(post("/mcp").servletPath("/mcp")
                        .header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
                        .content(malformed)).andReturn().getResponse();
                assertThat(response.getStatus()).isIn(400, 403);
                assertThat(response.getContentAsString()).doesNotContain("Exception", "stackTrace");
            }
            assertThat(context.getBean(Terminal.class).calls.get()).isZero();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class Fixture {
        @Bean @Primary JwtDecoder fixtureDecoder() throws Exception { return SignedJwtFixture.decoder(SignedJwtFixture.SETTINGS); }
        @Bean(name = "mcpServerJsonMapper") JsonMapper mapper() { return JsonMapper.builder().build(); }
        @Bean Terminal terminal() { return new Terminal(); }
    }

    @RestController
    static class Terminal {
        final AtomicInteger calls = new AtomicInteger();
        @PostMapping("/mcp") Map<String, Object> invoke(@RequestBody Map<String, Object> body) {
            calls.incrementAndGet();
            return body;
        }
    }
}
