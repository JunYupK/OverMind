package com.overmind.config;

import com.overmind.support.SignedJwtFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;

class SecurityTokenValidationTest {
    @Test
    void a_correctly_signed_allowed_identity_passes_the_production_validator_chain() throws Exception {
        assertThat(SignedJwtFixture.decoder(SignedJwtFixture.SETTINGS)
                .decode(SignedJwtFixture.token("memory:read", null)).getSubject()).isEqualTo("subject-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "issuer", "missing-issuer", "audience", "missing-audience", "expired", "not-before",
            "subject", "missing-subject", "missing-expiry", "malformed", "unsigned"})
    void invalid_bearer_tokens_fail_the_real_decoder(String defect) throws Exception {
        var decoder = SignedJwtFixture.decoder(SignedJwtFixture.SETTINGS);
        String token = SignedJwtFixture.token("memory:read memory:write", defect);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
