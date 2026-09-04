package com.overmind.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** L1. Spec §6: production refuses incomplete or unsafe security settings. */
class RequiredSettingsTest {

    private static final String ISSUER = "https://issuer.example.com/tenant";
    private static final String AUDIENCE = "overmind";
    private static final String ALLOWED_SUBJECT = "subject-1";
    private static final String CURSOR_SECRET = "overmind-test-cursor-key-".repeat(2);
    private static final ApplicationContextRunner PRODUCTION =
            new ApplicationContextRunner()
                    .withUserConfiguration(RequiredSettings.Validation.class)
                    .withPropertyValues("spring.profiles.active=production");

    @Test
    void complete_production_configuration_starts() {
        PRODUCTION.withPropertyValues(settings(ISSUER, AUDIENCE, ALLOWED_SUBJECT, CURSOR_SECRET))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void require_complete_preserves_identity_values_without_trimming() {
        String audience = " audience-with-boundary-space ";
        String subject = " subject-with-boundary-space ";

        RequiredSettings settings = new RequiredSettings(ISSUER, audience, subject, CURSOR_SECRET);

        settings.requireComplete();

        assertThat(settings.audience()).isEqualTo(audience);
        assertThat(settings.allowedSubject()).isEqualTo(subject);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSettings")
    void each_missing_setting_fails_production_startup(String setting, String[] properties) {
        assertStartupFails(properties);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankSettings")
    void each_blank_setting_fails_production_startup(String setting, String[] properties) {
        assertStartupFails(properties);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cursorSecrets")
    void cursor_secret_requires_at_least_32_utf8_bytes(String description, String secret, boolean starts) {
        PRODUCTION.withPropertyValues(settings(ISSUER, AUDIENCE, ALLOWED_SUBJECT, secret))
                .run(
                        context -> {
                            if (starts) {
                                assertThat(context).hasNotFailed();
                            } else {
                                assertThat(context).hasFailed();
                            }
                        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidIssuers")
    void issuer_must_be_an_https_absolute_uri_without_credentials_query_or_fragment(
            String description, String issuer) {
        assertStartupFails(settings(issuer, AUDIENCE, ALLOWED_SUBJECT, CURSOR_SECRET));
    }

    @Test
    void errors_and_to_string_do_not_expose_secret_or_identity() {
        String secret = "secret-not-for-exception-output-".repeat(2);
        String identity = "identity-not-for-exception-output";

        RequiredSettings settings =
                new RequiredSettings("http://issuer.example.com", identity, identity, secret);

        assertThat(settings.toString()).isEqualTo("RequiredSettings[redacted]");
        assertThat(settings.toString()).doesNotContain(secret, identity);
        org.assertj.core.api.Assertions.assertThatThrownBy(settings::requireComplete)
                .hasMessageNotContaining(secret)
                .hasMessageNotContaining(identity);

        PRODUCTION.withPropertyValues(
                        settings("http://issuer.example.com", identity, identity, secret))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(allFailureMessages(context.getStartupFailure()))
                                    .doesNotContain(secret, identity);
                        });
    }

    private static Stream<Arguments> missingSettings() {
        return Stream.of(
                Arguments.of("issuer", settings(null, AUDIENCE, ALLOWED_SUBJECT, CURSOR_SECRET)),
                Arguments.of("audience", settings(ISSUER, null, ALLOWED_SUBJECT, CURSOR_SECRET)),
                Arguments.of("allowed subject", settings(ISSUER, AUDIENCE, null, CURSOR_SECRET)),
                Arguments.of("cursor secret", settings(ISSUER, AUDIENCE, ALLOWED_SUBJECT, null)));
    }

    private static Stream<Arguments> blankSettings() {
        return Stream.of(
                Arguments.of("issuer", settings(" \t", AUDIENCE, ALLOWED_SUBJECT, CURSOR_SECRET)),
                Arguments.of("audience", settings(ISSUER, " \t", ALLOWED_SUBJECT, CURSOR_SECRET)),
                Arguments.of(
                        "allowed subject", settings(ISSUER, AUDIENCE, " \t", CURSOR_SECRET)),
                Arguments.of("cursor secret", settings(ISSUER, AUDIENCE, ALLOWED_SUBJECT, " \t")));
    }

    private static Stream<Arguments> cursorSecrets() {
        String thirtyOneBytes = "a".repeat(31);
        String thirtyTwoBytes = "a".repeat(32);
        String multiByteThirtyOne = "가".repeat(10) + "a";
        String multiByteThirtyTwo = "가".repeat(10) + "ab";
        assertThat(multiByteThirtyOne.getBytes(StandardCharsets.UTF_8)).hasSize(31);
        assertThat(multiByteThirtyTwo.getBytes(StandardCharsets.UTF_8)).hasSize(32);
        return Stream.of(
                Arguments.of("31 ASCII bytes", thirtyOneBytes, false),
                Arguments.of("32 ASCII bytes", thirtyTwoBytes, true),
                Arguments.of("31 UTF-8 bytes with multibyte characters", multiByteThirtyOne, false),
                Arguments.of("32 UTF-8 bytes with multibyte characters", multiByteThirtyTwo, true));
    }

    private static Stream<Arguments> invalidIssuers() {
        return Stream.of(
                Arguments.of("HTTP scheme", "http://issuer.example.com"),
                Arguments.of("relative URI", "/issuer"),
                Arguments.of("missing host", "https:/issuer"),
                Arguments.of("credentials", "https://user@issuer.example.com"),
                Arguments.of("query", "https://issuer.example.com/tenant?debug=true"),
                Arguments.of("fragment", "https://issuer.example.com/tenant#fragment"));
    }

    private static void assertStartupFails(String[] properties) {
        PRODUCTION.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());
    }

    private static String[] settings(
            String issuer, String audience, String allowedSubject, String cursorSecret) {
        List<String> properties = new ArrayList<>();
        add(properties, "issuer", issuer);
        add(properties, "audience", audience);
        add(properties, "allowed-subject", allowedSubject);
        add(properties, "cursor-secret", cursorSecret);
        return properties.toArray(String[]::new);
    }

    private static void add(List<String> properties, String name, String value) {
        if (value != null) {
            properties.add("overmind.security." + name + "=" + value);
        }
    }

    private static String allFailureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage());
        }
        return messages.toString();
    }
}
