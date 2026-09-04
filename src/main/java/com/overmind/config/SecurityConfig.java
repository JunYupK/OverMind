package com.overmind.config;

import com.overmind.adapter.in.mcp.McpHttpErrors;
import com.overmind.adapter.in.mcp.McpScopeFilter;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.json.JsonMapper;

/** OIDC bearer authentication applies on every MCP request, independently of MCP sessions. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RequiredSettings.class)
public class SecurityConfig {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(RequiredSettings settings) {
        settings.requireComplete();
        // Discovery/JWKS is deferred to the first authenticated request; no fallback decoder exists.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(settings.issuer()).build();
            decoder.setJwtValidator(validators(settings));
            return decoder;
        });
    }

    public static OAuth2TokenValidator<Jwt> validators(RequiredSettings settings) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()),
                new JwtClaimValidator<List<String>>("aud",
                        audience -> audience != null && audience.contains(settings.audience())),
                new JwtClaimValidator<String>("sub",
                        subject -> settings.allowedSubject().equals(subject)),
                new JwtClaimValidator<Instant>("exp", expiry -> expiry != null));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtDecoder decoder, RequiredSettings settings,
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper) throws Exception {
        DefaultBearerTokenResolver tokens = new DefaultBearerTokenResolver();
        tokens.setAllowUriQueryParameter(false);
        tokens.setAllowFormEncodedBodyParameter(false);
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/mcp").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> McpHttpErrors.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, failure) -> McpHttpErrors.forbidden(response)))
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(tokens)
                        .jwt(jwt -> jwt.decoder(decoder))
                        // RFC 9728. MCP 클라이언트는 이 문서로 인가 서버를 찾는다.
                        // authorization_servers와 scopes_supported에는 프레임워크 기본값이 없다.
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(document -> document
                                        .authorizationServer(settings.issuer())
                                        .scope("memory:read")
                                        .scope("memory:write")
                                        .resourceName("OverMind")
                                        // 기본값이 true다. mTLS를 쓰지 않으므로 끈다 —
                                        // 하지 않는 보안 속성을 광고하면 안 된다.
                                        .tlsClientCertificateBoundAccessTokens(false)))
                        .authenticationEntryPoint((request, response, failure) -> McpHttpErrors.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, failure) -> McpHttpErrors.forbidden(response)))
                .addFilterAfter(new McpScopeFilter(mapper), AuthorizationFilter.class)
                .build();
    }
}
