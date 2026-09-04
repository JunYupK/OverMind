package com.overmind.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.overmind.config.RequiredSettings;
import com.overmind.config.SecurityConfig;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Signed locally; the decoder shares the production validator chain, never a mock context. */
public final class SignedJwtFixture {
    public static final String ISSUER = "https://issuer.example.com";
    public static final String SUBJECT = "subject-1";
    public static final String MARKER = "T12_PRIVATE_VALUE";
    public static final RequiredSettings SETTINGS = new RequiredSettings(
            ISSUER, "overmind", SUBJECT, "overmind-test-cursor-key-".repeat(2));
    private static final RSAKey KEY = key();
    private static final RSAKey OTHER_KEY = key();

    public static NimbusJwtDecoder decoder(RequiredSettings settings) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(SecurityConfig.validators(settings));
        return decoder;
    }
    public static String token(String scope, String defect) throws JOSEException {
        return token(scope, defect, Map.of());
    }

    /** Additional synthetic claims allow each privacy test to identify its own leaked values. */
    public static String token(String scope, String defect, Map<String, Object> extraClaims) throws JOSEException {
        if ("malformed".equals(defect)) return MARKER;
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUBJECT)
                .audience("overmind").issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        if (scope != null) claims.claim("scope", scope);
        if (defect != null) switch (defect) {
            case "issuer" -> claims.issuer("https://" + MARKER + ".example.com");
            case "missing-issuer" -> claims.issuer(null);
            case "audience" -> claims.audience(MARKER);
            case "missing-audience" -> claims.audience((String) null);
            case "subject" -> claims.subject(MARKER);
            case "missing-subject" -> claims.subject(null);
            case "expired" -> claims.expirationTime(Date.from(now.minusSeconds(300)));
            case "not-before" -> claims.notBeforeTime(Date.from(now.plusSeconds(300)));
            case "missing-expiry" -> claims.expirationTime(null);
            default -> { }
        }
        extraClaims.forEach(claims::claim);
        if ("unsigned".equals(defect)) return new com.nimbusds.jwt.PlainJWT(claims.build()).serialize();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims.build());
        signed.sign(new RSASSASigner("signature".equals(defect) ? OTHER_KEY : KEY));
        return signed.serialize();
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).generate(); }
        catch (JOSEException failure) { throw new IllegalStateException("Cannot create test key", failure); }
    }

}
