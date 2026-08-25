package com.kama.jchatmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

/**
 * paper 表幂等建表初始化器
 * 应用启动时执行 classpath:db/paper-schema.sql（全部为 CREATE ... IF NOT EXISTS，可安全重复执行）
 * 通过 PaperSchemaConfiguration 的 @Bean(initMethod = "initialize") 注册，不使用 @Component
 */
@Slf4j
public class PaperSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public PaperSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/paper-schema.sql"));
            log.info("paper 表结构初始化完成（幂等）");
        } catch (Exception e) {
            throw new IllegalStateException("paper 表结构初始化失败", e);
        }
    }
}
