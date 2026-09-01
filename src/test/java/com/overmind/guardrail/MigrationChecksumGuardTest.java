package com.overmind.guardrail;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Flyway는 forward-only다. 이미 커밋된 마이그레이션을 고치면
 * 이미 적용된 환경과 새 환경의 스키마가 갈라진다.
 */
@Tag("guardrail")
class MigrationChecksumGuardTest {

    static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    static final Path CHECKSUM_FILE = Path.of("docs/harness/migration-checksums.txt");

    @Test
    void committed_migrations_are_unchanged() throws IOException {
        Map<String, String> actual = currentChecksums();
        Map<String, String> recorded = recordedChecksums();

        assertThat(actual)
                .as(
                        "마이그레이션이 변경되었습니다. 기존 파일을 고치지 말고 새 버전을 추가하세요. "
                                + "새 파일을 추가한 경우에만 ./gradlew updateMigrationChecksums 를 실행합니다")
                .isEqualTo(recorded);
    }

    static Map<String, String> currentChecksums() throws IOException {
        if (!Files.isDirectory(MIGRATION_DIR)) {
            return Map.of();
        }
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .collect(
                            toMap(
                                    p -> p.getFileName().toString(),
                                    MigrationChecksumGuardTest::sha256,
                                    (a, b) -> a,
                                    LinkedHashMap::new));
        }
    }

    static Map<String, String> recordedChecksums() throws IOException {
        Map<String, String> recorded = new LinkedHashMap<>();
        if (!Files.exists(CHECKSUM_FILE)) {
            return recorded;
        }
        for (String line : Files.readAllLines(CHECKSUM_FILE)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            recorded.put(parts[0], parts[1]);
        }
        return recorded;
    }

    static String sha256(Path file) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(Files.readString(file).replace("\r\n", "\n")
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("해시 계산 실패: " + file, e);
        }
    }
}
