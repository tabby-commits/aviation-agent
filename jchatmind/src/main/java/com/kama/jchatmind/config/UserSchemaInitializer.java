package com.kama.jchatmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * 用户表幂等初始化器
 * 执行 classpath:db/user-schema.sql 建表；初始管理员账号经 Java 侧 BCrypt 哈希后种入（仅当不存在时）
 * 通过 UserSchemaConfiguration 的 @Bean(initMethod = "initialize") 注册
 */
@Slf4j
public class UserSchemaInitializer {

    /** 初始管理员账号（密码哈希存库，不落明文） */
    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_PASSWORD = "22374493";

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserSchemaInitializer(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public void initialize() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            // 显式 UTF-8：Windows 平台默认字符集为 GBK
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("db/user-schema.sql"), StandardCharsets.UTF_8));
            seedAdmin();
            log.info("用户表结构初始化完成（幂等），初始管理员账号已就绪");
        } catch (Exception e) {
            throw new IllegalStateException("用户表结构初始化失败", e);
        }
    }

    private void seedAdmin() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, ADMIN_USERNAME);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                ADMIN_USERNAME, passwordEncoder.encode(ADMIN_PASSWORD));
    }
}
