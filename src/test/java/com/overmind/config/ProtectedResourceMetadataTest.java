package com.overmind.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.overmind.support.SignedJwtFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** L1. RFC 9728 메타데이터가 인증 없이 열리고 MCP 클라이언트가 필요한 값을 담는다. */
class ProtectedResourceMetadataTest {

    private static final String METADATA = "/.well-known/oauth-protected-resource/mcp";
    private static final String RPC = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class, Fixture.class)
            .withPropertyValues(
                    "overmind.security.issuer=" + SignedJwtFixture.ISSUER,
                    "overmind.security.audience=overmind",
                    "overmind.security.allowed-subject=" + SignedJwtFixture.SUBJECT,
                    "overmind.security.cursor-secret=" + "test-cursor-key-".repeat(3));

    @Test
    void the_metadata_document_is_served_without_a_token() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            var response = mvc.perform(get(METADATA).servletPath(METADATA)).andReturn().getResponse();

            assertThat(response.getStatus())
                    .as("MCP 클라이언트는 토큰 없이 이 문서를 읽어 인가 서버를 찾는다")
                    .isEqualTo(200);
            assertThat(response.getContentAsString())
                    .contains("\"authorization_servers\"")
                    .contains(SignedJwtFixture.ISSUER)
                    .contains("memory:read")
                    .contains("memory:write");
        });
    }

    @Test
    void the_metadata_does_not_advertise_security_properties_we_do_not_have() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var body = mvc.perform(get(METADATA).servletPath(METADATA))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("mTLS를 쓰지 않는데 tls_client_certificate_bound_access_tokens=true를 "
                            + "광고하면 그것을 신뢰하는 클라이언트를 오도한다. 프레임워크 기본값이 true다")
                    .doesNotContain("\"tls_client_certificate_bound_access_tokens\":true");
            assertThat(body)
                    .as("M0에 delete scope는 없다 (C-7). M6까지 광고하지 않는다")
                    .doesNotContain("memory:delete");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class Fixture {
        @Bean @Primary JwtDecoder fixtureDecoder() throws Exception {
            return SignedJwtFixture.decoder(SignedJwtFixture.SETTINGS);
        }
        @Bean(name = "mcpServerJsonMapper") JsonMapper mapper() { return JsonMapper.builder().build(); }
    }
}
