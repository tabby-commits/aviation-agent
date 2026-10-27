package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 用户表相关 Bean 配置
 */
@Configuration
public class UserSchemaConfiguration {

    @Bean(initMethod = "initialize")
    public UserSchemaInitializer userSchemaInitializer(JdbcTemplate jdbcTemplate,
                                                       BCryptPasswordEncoder passwordEncoder) {
        return new UserSchemaInitializer(jdbcTemplate, passwordEncoder);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
