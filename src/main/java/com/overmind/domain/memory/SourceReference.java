package com.overmind.domain.memory;

import com.overmind.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * observation이 어디서 왔는지. 제품 이름에 중립적인 세 필드다.
 *
 * <p>예: client {@code "example-client"}, conversation {@code "conv-1"},
 * message {@code "msg-1"}.
 */
public final class SourceReference {

    public static final int MAX_CLIENT_BYTES = 128;
    public static final int MAX_ID_BYTES = 512;

    private final String client;
    private final String conversationId;
    private final String messageId;

    private SourceReference(String client, String conversationId, String messageId) {
        this.client = client;
        this.conversationId = conversationId;
        this.messageId = messageId;
    }

    public static SourceReference of(String client, String conversationId, String messageId) {
        return new SourceReference(
                required(client, MAX_CLIENT_BYTES, "source client"),
                required(conversationId, MAX_ID_BYTES, "source conversation id"),
                required(messageId, MAX_ID_BYTES, "source message id"));
    }

    private static String required(String raw, int maxBytes, String label) {
        if (raw == null || raw.isBlank()) {
            throw new DomainValidationException(label + " 가 비어 있습니다");
        }
        int size = raw.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxBytes) {
            throw new DomainValidationException(
                    label + " 가 " + maxBytes + " bytes를 넘습니다 (" + size + " bytes)");
        }
        return raw;
    }

    public String client() {
        return client;
    }

    public String conversationId() {
        return conversationId;
    }

    public String messageId() {
        return messageId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SourceReference that
                && client.equals(that.client)
                && conversationId.equals(that.conversationId)
                && messageId.equals(that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(client, conversationId, messageId);
    }
}
