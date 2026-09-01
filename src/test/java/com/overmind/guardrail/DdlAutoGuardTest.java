package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 스키마 변경 경로를 Flyway 하나로 묶는다.
 *
 * <p>ddl-auto가 update나 create가 되면 스키마가 마이그레이션 밖에서 바뀌고,
 * 그 순간 마이그레이션 해시 가드가 무의미해진다.
 */
@Tag("guardrail")
class DdlAutoGuardTest {

    private static final Pattern DDL_AUTO = Pattern.compile("ddl-auto:\\s*(\\S+)");

    @Test
    void ddl_auto_is_validate() throws IOException {
        Path config = Path.of("src/main/resources/application.yml");
        assertThat(config).exists();

        String content = Files.readString(config);
        Matcher matcher = DDL_AUTO.matcher(content);

        assertThat(matcher.find())
                .as("application.yml에 ddl-auto 설정이 있어야 합니다")
                .isTrue();
        assertThat(matcher.group(1))
                .as("스키마는 Flyway만 바꾼다. ddl-auto는 validate 고정입니다")
                .isEqualTo("validate");
    }
}
