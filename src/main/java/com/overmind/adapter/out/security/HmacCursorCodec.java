package com.overmind.adapter.out.security;

import com.overmind.application.MemoryErrorCode;
import com.overmind.application.MemoryException;
import com.overmind.application.memory.RecallCursor;
import com.overmind.application.port.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Spec §5.3: a stateless cursor only this server can mint.
 *
 * <p>Token shape is {@code v1.<payload>.<signature>}, both parts base64url without padding.
 * The payload carries the keyset position and the subject-filter fingerprint — never content,
 * source identifiers, or raw subject ids.
 *
 * <p>Every rejection is {@code INVALID_CURSOR}. Falling back to the first page would silently
 * re-serve observations the caller already walked, which reads as data loss from the outside.
 */
public class HmacCursorCodec implements CursorCodec {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final int PAYLOAD_FIELDS = 4;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public HmacCursorCodec(byte[] secret) {
        if (secret == null || secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "cursor HMAC secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.secret = secret.clone();
    }

    @Override
    public String encode(RecallCursor cursor) {
        String payload =
                instantField(cursor.observedAt())
                        + "|"
                        + instantField(cursor.createdAt())
                        + "|"
                        + cursor.id()
                        + "|"
                        + cursor.filterFingerprint();
        String signed =
                VERSION + "." + ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signed + "." + ENCODER.encodeToString(sign(signed));
    }

    @Override
    public RecallCursor decode(String token, String expectedFingerprint) {
        if (token == null || token.isBlank()) {
            throw invalidCursor();
        }
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) {
            throw invalidCursor();
        }
        String signed = token.substring(0, lastDot);
        if (!signed.startsWith(VERSION + ".")) {
            throw invalidCursor();
        }

        byte[] presentedSignature;
        String payload;
        try {
            presentedSignature = DECODER.decode(token.substring(lastDot + 1));
            payload =
                    new String(
                            DECODER.decode(signed.substring(VERSION.length() + 1)),
                            StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedBase64) {
            throw invalidCursor();
        }

        // Constant-time compare: String.equals leaks how much of a forged signature matched.
        if (!MessageDigest.isEqual(sign(signed), presentedSignature)) {
            throw invalidCursor();
        }

        String[] fields = payload.split("\\|", -1);
        if (fields.length != PAYLOAD_FIELDS || !fields[3].equals(expectedFingerprint)) {
            throw invalidCursor();
        }
        try {
            return new RecallCursor(
                    parseInstant(fields[0]),
                    parseInstant(fields[1]),
                    UUID.fromString(fields[2]),
                    fields[3]);
        } catch (RuntimeException malformedPayload) {
            throw invalidCursor();
        }
    }

    private static String instantField(Instant instant) {
        return instant.getEpochSecond() + ":" + instant.getNano();
    }

    private static Instant parseInstant(String field) {
        String[] parts = field.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("malformed instant field");
        }
        return Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    private byte[] sign(String signed) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(signed.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /** Spec §5.5 and §8: the message names the failure, never the token that caused it. */
    private static MemoryException invalidCursor() {
        return new MemoryException(MemoryErrorCode.INVALID_CURSOR, "cursor is not valid");
    }
}
