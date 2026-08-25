package com.kama.jchatmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * 分类体系/论文归属/参数证据三表幂等初始化器
 * 执行 classpath:db/taxonomy-schema.sql（建表 IF NOT EXISTS + 种子 ON CONFLICT DO NOTHING，可安全重复执行）
 * 通过 TaxonomySchemaConfiguration 的 @Bean(initMethod = "initialize") 注册
 */
@Slf4j
public class TaxonomySchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public TaxonomySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            // 显式 UTF-8：Windows 平台默认字符集为 GBK，种子含中文必须指定编码
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("db/taxonomy-schema.sql"), StandardCharsets.UTF_8));
            log.info("分类体系/论文归属/参数证据表结构初始化完成（幂等）");
        } catch (Exception e) {
            throw new IllegalStateException("taxonomy 相关表结构初始化失败", e);
        }
    }
}
