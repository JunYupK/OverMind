package com.overmind.application.port;

import com.overmind.application.memory.RecallCursor;

/**
 * Encodes and verifies recall cursors.
 *
 * <p>The signing scheme lives in an adapter: spec §5.3 requires a server-only signature,
 * and the key material that provides it is deployment configuration, not domain logic.
 */
public interface CursorCodec {

    String encode(RecallCursor cursor);

    /**
     * Decodes a cursor, rejecting anything not produced by this server for
     * {@code expectedFingerprint}.
     *
     * @throws com.overmind.application.MemoryException with {@code INVALID_CURSOR} when the
     *     token is malformed, unsigned by this server, of an unknown version, or bound to a
     *     different subject filter
     */
    RecallCursor decode(String token, String expectedFingerprint);
}
