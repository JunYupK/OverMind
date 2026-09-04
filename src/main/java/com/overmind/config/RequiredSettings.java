package com.overmind.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Required security inputs. Values are deliberately excluded from diagnostics. */
@ConfigurationProperties("overmind.security")
public record RequiredSettings(String issuer, String audience, String allowedSubject, String cursorSecret) {

    private static final int MIN_CURSOR_SECRET_BYTES = 32;

    /** Verifies all settings without altering caller identity values. */
    public void requireComplete() {
        requireNonBlank("issuer", issuer);
        requireNonBlank("audience", audience);
        requireNonBlank("allowed subject", allowedSubject);
        requireNonBlank("cursor secret", cursorSecret);
        validateIssuer();
        if (cursorSecret.getBytes(StandardCharsets.UTF_8).length < MIN_CURSOR_SECRET_BYTES) {
            throw invalid("cursor secret must be at least 32 UTF-8 bytes");
        }
    }

    @Override
    public String toString() {
        return "RequiredSettings[redacted]";
    }

    private void validateIssuer() {
        URI parsed;
        try {
            parsed = URI.create(issuer);
        } catch (IllegalArgumentException invalidUri) {
            throw invalid("issuer must be an HTTPS absolute URI with a host");
        }
        if (!parsed.isAbsolute()
                || !"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw invalid("issuer must be an HTTPS absolute URI without credentials, query, or fragment");
        }
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Invalid overmind.security setting: " + reason);
    }

    /** Activates eager fail-closed validation only for production deployments. */
    @Configuration(proxyBeanMethods = false)
    @Profile("production")
    @EnableConfigurationProperties(RequiredSettings.class)
    public static class Validation {

        @Bean
        InitializingBean requiredSettingsValidation(RequiredSettings settings) {
            return settings::requireComplete;
        }
    }
}
