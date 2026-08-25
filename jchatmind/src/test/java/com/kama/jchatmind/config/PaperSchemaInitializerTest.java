package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * paper 表幂等建表初始化器测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperSchemaInitializerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void paperTableShouldExistAfterContextStartup() {
        // to_regclass 返回表在 public 模式下的注册名；不存在时为 null
        String regClass = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.paper')::text", String.class);
        assertNotNull(regClass, "paper 表应在应用启动后存在");
        assertEquals("paper", regClass);
    }

    @Test
    public void docIdUniqueConstraintShouldExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conrelid = 'public.paper'::regclass AND conname = 'uk_paper_doc_id'",
                Integer.class);
        assertNotNull(count);
        assertEquals(1, count, "doc_id 唯一约束应存在（幂等 upsert 的前提）");
    }
}
