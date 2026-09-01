package com.overmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1. INV-02 검사 도구 자체를 검증한다.
 *
 * <p>누출을 잡지 못하는 검사 도구는 검사하지 않는 것보다 나쁘다. 통과했다는
 * 잘못된 신호를 주기 때문이다.
 */
class LogCaptureTest {

    private static final Logger log = LoggerFactory.getLogger(LogCaptureTest.class);

    @Test
    void detects_a_leaked_payload() {
        try (LogCapture capture = LogCapture.start()) {
            log.info("사용자 발화를 그대로 로그에 남긴다: {}", "MAGIC-LEAK-1");

            assertThatThrownBy(() -> capture.assertNoOccurrenceOf("MAGIC-LEAK-1"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("MAGIC-LEAK-1");
        }
    }

    @Test
    void passes_when_only_metadata_is_logged() {
        try (LogCapture capture = LogCapture.start()) {
            log.info("observation 저장 완료 id={} count={}", 42L, 3);

            capture.assertNoOccurrenceOf("MAGIC-LEAK-1");
        }
    }

    @Test
    void collects_rendered_lines() {
        try (LogCapture capture = LogCapture.start()) {
            log.warn("pending job {}건", 7);

            assertThat(capture.lines()).anyMatch(line -> line.contains("pending job 7건"));
        }
    }

    @Test
    void restores_root_log_level_on_close() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Level levelBefore = rootLogger.getLevel();

        try (LogCapture capture = LogCapture.start()) {
            log.info("아무 로그나 하나 남긴다");
        }

        assertThat(rootLogger.getLevel()).isEqualTo(levelBefore);
    }
}
