package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 评价相关 Bean 配置
 */
@Configuration
public class EvaluationSchemaConfiguration {

    @Bean(initMethod = "initialize")
    public EvaluationSchemaInitializer evaluationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new EvaluationSchemaInitializer(jdbcTemplate);
    }
}
