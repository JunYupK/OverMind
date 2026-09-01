package com.overmind;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L1. 아키텍처 경계가 되는 최상위 패키지가 실제로 존재하는지 확인한다.
 * ArchUnit 규칙(Task 5)은 이 패키지들을 대상으로 삼는다.
 */
class PackageLayoutTest {

    private static final List<String> REQUIRED_PACKAGES =
            List.of("domain", "application", "adapter/in", "adapter/out", "config");

    @Test
    void base_packages_exist() {
        for (String pkg : REQUIRED_PACKAGES) {
            Path dir = Path.of("src/main/java/com/overmind", pkg);
            assertThat(dir)
                    .as("아키텍처 경계 패키지 %s 가 없습니다", pkg)
                    .isDirectory();
        }
    }
}
