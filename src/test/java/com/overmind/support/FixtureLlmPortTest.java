package com.overmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.application.port.LlmRequest;
import com.overmind.application.port.LlmResponse;
import com.overmind.application.port.PromptVersions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** L1. 픽스처 재생·녹화 장치 자체를 검증한다. */
class FixtureLlmPortTest {

    @Test
    void replays_a_recorded_response(@TempDir Path root) throws Exception {
        LlmRequest request = new LlmRequest(PromptVersions.EXTRACTOR, "안녕하세요");
        FixtureLlmPort port = new FixtureLlmPort(root, null, false);

        Path file = port.fixtureFile(request);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"content\":\"녹화된 응답\"}");

        LlmResponse response = port.complete(request);

        assertThat(response.content()).isEqualTo("녹화된 응답");
    }

    @Test
    void fails_loudly_when_fixture_is_missing(@TempDir Path root) {
        LlmRequest request = new LlmRequest(PromptVersions.EXTRACTOR, "픽스처 없는 프롬프트");
        FixtureLlmPort port = new FixtureLlmPort(root, null, false);

        assertThatThrownBy(() -> port.complete(request))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("overmind.llm.record");
    }

    @Test
    void records_through_the_delegate_and_writes_the_fixture(@TempDir Path root) throws Exception {
        LlmRequest request = new LlmRequest(PromptVersions.EXTRACTOR, "새 프롬프트");
        FixtureLlmPort port =
                new FixtureLlmPort(root, req -> new LlmResponse("실제 모델 응답"), true);

        LlmResponse response = port.complete(request);

        assertThat(response.content()).isEqualTo("실제 모델 응답");
        assertThat(Files.readString(port.fixtureFile(request))).contains("실제 모델 응답");
    }

    @Test
    void same_prompt_maps_to_the_same_fixture_file(@TempDir Path root) {
        FixtureLlmPort port = new FixtureLlmPort(root, null, false);

        Path a = port.fixtureFile(new LlmRequest(PromptVersions.EXTRACTOR, "같은 프롬프트"));
        Path b = port.fixtureFile(new LlmRequest(PromptVersions.EXTRACTOR, "같은 프롬프트"));
        Path c = port.fixtureFile(new LlmRequest(PromptVersions.EXTRACTOR, "다른 프롬프트"));

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }
}
