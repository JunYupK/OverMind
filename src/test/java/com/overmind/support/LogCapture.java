package com.overmind.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * INV-02 검사 도구. 루트 로거에 붙어 실행 중 발생한 로그를 모은다.
 *
 * <p>사용법:
 *
 * <pre>{@code
 * try (LogCapture capture = LogCapture.start()) {
 *     rememberMemoryService.handle(요청);
 *     capture.assertNoOccurrenceOf("사용자가 실제로 말한 문장", "canonical 값");
 * }
 * }</pre>
 *
 * <p>{@code start()}는 루트 로거 레벨을 {@code TRACE}로 낮춰 모든 로그를 캡처한다.
 * L1 테스트가 전부 한 JVM에서 실행되므로, 이 레벨 변경이 {@code close()} 이후에도
 * 남아 있으면 뒤에 실행되는 테스트의 로그 레벨 기대치를 오염시킨다. 그래서
 * {@code start()}는 원래 레벨(상속 상태를 뜻하는 {@code null}일 수도 있다)을
 * 기억해 두었다가 {@code close()}에서 정확히 그 값으로 되돌린다.
 */
public final class LogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger rootLogger;
    private final ListAppender<ILoggingEvent> appender;
    private final Level originalLevel;

    private LogCapture(
            ch.qos.logback.classic.Logger rootLogger,
            ListAppender<ILoggingEvent> appender,
            Level originalLevel) {
        this.rootLogger = rootLogger;
        this.appender = appender;
        this.originalLevel = originalLevel;
    }

    public static LogCapture start() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();

        Level originalLevel = rootLogger.getLevel();

        rootLogger.addAppender(appender);
        rootLogger.setLevel(Level.TRACE);
        return new LogCapture(rootLogger, appender, originalLevel);
    }

    /** 캡처된 로그를 렌더링된 문자열로 돌려준다. 예외 스택트레이스 메시지도 포함한다. */
    public List<String> lines() {
        return appender.list.stream()
                .flatMap(
                        event ->
                                Stream.concat(
                                        Stream.of(event.getFormattedMessage()),
                                        event.getThrowableProxy() == null
                                                ? Stream.empty()
                                                : Stream.of(event.getThrowableProxy().getMessage())))
                .toList();
    }

    /** 주어진 문자열이 어느 로그 줄에도 나타나지 않아야 한다. */
    public void assertNoOccurrenceOf(String... forbidden) {
        List<String> lines = lines();
        for (String secret : forbidden) {
            for (String line : lines) {
                if (line != null && line.contains(secret)) {
                    throw new AssertionError(
                            "INV-02 위반: 로그에 민감 값이 나타났습니다 — \""
                                    + secret
                                    + "\""
                                    + System.lineSeparator()
                                    + "  로그 줄: "
                                    + line);
                }
            }
        }
    }

    @Override
    public void close() {
        rootLogger.detachAppender(appender);
        appender.stop();
        rootLogger.setLevel(originalLevel);
    }
}
