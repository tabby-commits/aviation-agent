package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分类体系相关 Bean 配置
 */
@Configuration
public class TaxonomySchemaConfiguration {

    @Bean(initMethod = "initialize")
    public TaxonomySchemaInitializer taxonomySchemaInitializer(JdbcTemplate jdbcTemplate) {
        return new TaxonomySchemaInitializer(jdbcTemplate);
    }
}
