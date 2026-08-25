package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 论文相关 Bean 配置
 */
@Configuration
public class PaperSchemaConfiguration {

    /**
     * 建表逻辑作为 Bean 的初始化回调执行，保证任何依赖 paper 表的 Bean 使用前表已存在
     */
    @Bean(initMethod = "initialize")
    public PaperSchemaInitializer paperSchemaInitializer(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new PaperSchemaInitializer(jdbcTemplate);
    }
}
