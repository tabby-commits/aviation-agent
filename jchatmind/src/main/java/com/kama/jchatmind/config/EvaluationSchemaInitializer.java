package com.kama.jchatmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * 评价运行/检查项表幂等初始化器（显式 UTF-8）
 */
@Slf4j
public class EvaluationSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public EvaluationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("db/evaluation-schema.sql"), StandardCharsets.UTF_8));
            log.info("评价运行/检查项表结构初始化完成（幂等）");
        } catch (Exception e) {
            throw new IllegalStateException("evaluation 相关表结构初始化失败", e);
        }
    }
}
