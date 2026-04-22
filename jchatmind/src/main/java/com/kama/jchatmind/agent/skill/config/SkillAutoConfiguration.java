package com.kama.jchatmind.agent.skill.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 自动配置类
 * <p>
 * 当 jchatmind.skill.enabled=true（默认）时启用
 */
@Configuration
@EnableConfigurationProperties(SkillProperties.class)
@ConditionalOnProperty(prefix = "jchatmind.skill", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillAutoConfiguration {
    // SkillMdParser、SkillScanner、SkillServiceImpl 都使用 @Component 注解
    // Spring 会自动扫描并注册这些 Bean
    // 此类主要用于启用 SkillProperties 配置绑定和条件化配置
}