package com.overmind.application.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Recall keyset position, pinned to the subject filter it was produced for.
 *
 * <p>Spec §5.3 keeps raw subject ids out of the cursor payload, so the filter travels as a
 * fingerprint. A cursor presented against a different filter is rejected rather than
 * silently restarting the walk: the keyset position means nothing under another filter.
 */
public record RecallCursor(
        Instant observedAt, Instant createdAt, UUID id, String filterFingerprint) {

    private static final int FINGERPRINT_LENGTH = 16;

    /**
     * Order-independent digest of the subject ids a page was read under.
     *
     * <p>USER and PROJECT resolve to the same pair regardless of argument order, so the
     * fingerprint sorts before hashing. Truncation to {@value #FINGERPRINT_LENGTH} hex
     * characters is sound here because the value is compared, never inverted.
     */
    public static String filterFingerprint(List<UUID> subjectIds) {
        String joined =
                subjectIds.stream().map(UUID::toString).sorted().reduce("", (a, b) -> a + "," + b);
        return HexFormat.of()
                .formatHex(digest().digest(joined.getBytes(StandardCharsets.UTF_8)))
                .substring(0, FINGERPRINT_LENGTH);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
