package com.kama.jchatmind.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEnvironmentIsolationTest {

    @Test
    @DisplayName("测试环境默认使用独立数据库和文件目录")
    void testEnvironmentDoesNotDefaultToProductionStorage() throws IOException {
        String config = Files.readString(Path.of("src/test/resources/application.yaml"));

        assertAll(
                () -> assertTrue(config.contains(
                        "${JCHATMIND_TEST_DB_URL:jdbc:postgresql://localhost:5432/jchatmind_test}")),
                () -> assertTrue(config.contains("${JCHATMIND_TEST_DB_PASSWORD:}")),
                () -> assertTrue(config.contains("base-path: ./data/test-documents")),
                () -> assertFalse(config.contains("jdbc:postgresql://localhost:5432/jchatmind\n")),
                () -> assertFalse(config.contains("password: 22374493"))
        );
    }
}
