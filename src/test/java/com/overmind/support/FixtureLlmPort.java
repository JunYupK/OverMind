package com.overmind.support;

import tools.jackson.databind.ObjectMapper;
import com.overmind.application.port.LlmPort;
import com.overmind.application.port.LlmRequest;
import com.overmind.application.port.LlmResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * L2용 LLM 포트. 녹화된 응답을 재생하거나, 녹화 모드에서 실제 포트를 호출하고 픽스처를 갱신한다.
 *
 * <p>파일 경로는 {@code <root>/<promptVersion>/<프롬프트 SHA-256 앞 16자>.json}이다.
 * 프롬프트가 같으면 항상 같은 파일을 가리키므로, 테스트가 케이스 이름을 관리할 필요가 없다.
 */
public final class FixtureLlmPort implements LlmPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final LlmPort delegate;
    private final boolean recording;

    /** 재생 전용. */
    public FixtureLlmPort(Path root, LlmPort delegate) {
        this(root, delegate, Boolean.getBoolean("overmind.llm.record"));
    }

    public FixtureLlmPort(Path root, LlmPort delegate, boolean recording) {
        this.root = root;
        this.delegate = delegate;
        this.recording = recording;
    }

    /** 저장소의 실제 픽스처를 재생하는 기본 인스턴스. */
    public static FixtureLlmPort replaying() {
        return new FixtureLlmPort(Path.of("src/test/resources/llm-fixtures"), null, false);
    }

    /** 실제 포트를 감싸 녹화하는 인스턴스. */
    public static FixtureLlmPort recording(LlmPort real) {
        return new FixtureLlmPort(Path.of("src/test/resources/llm-fixtures"), real, true);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Path file = fixtureFile(request);

        if (recording) {
            if (delegate == null) {
                throw new IllegalStateException(
                        "녹화 모드인데 실제 LlmPort가 주입되지 않았습니다");
            }
            LlmResponse fresh = delegate.complete(request);
            write(file, fresh);
            return fresh;
        }

        if (!Files.exists(file)) {
            throw new AssertionError(
                    "LLM 픽스처가 없습니다: "
                            + file
                            + System.lineSeparator()
                            + "재녹화: ./gradlew integrationTest -Dovermind.llm.record=true");
        }
        return read(file);
    }

    /** 이 요청이 매핑되는 픽스처 파일 경로. */
    public Path fixtureFile(LlmRequest request) {
        return root.resolve(request.promptVersion()).resolve(keyOf(request.prompt()) + ".json");
    }

    static String keyOf(String prompt) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(prompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LlmResponse read(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file), LlmResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException("픽스처를 읽지 못했습니다: " + file, e);
        }
    }

    private void write(Path file, LlmResponse response) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(response));
        } catch (IOException e) {
            throw new UncheckedIOException("픽스처를 쓰지 못했습니다: " + file, e);
        }
    }
}
