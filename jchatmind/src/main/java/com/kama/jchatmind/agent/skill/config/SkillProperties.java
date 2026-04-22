package com.kama.jchatmind.agent.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 配置属性
 */
@Data
@ConfigurationProperties(prefix = "jchatmind.skill")
public class SkillProperties {

    /**
     * 是否启用 Skill 功能
     */
    private boolean enabled = true;

    /**
     * Skill 根目录列表
     */
    private List<String> scanDirectories = new ArrayList<>();

    /**
     * 文件编码
     */
    private String encoding = "UTF-8";

    /**
     * Skill 类型：FIXED（所有 Agent 都可用）或 OPTIONAL（按 Agent 配置）
     */
    private String type = "OPTIONAL";
}