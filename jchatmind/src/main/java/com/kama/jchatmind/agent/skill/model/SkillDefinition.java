package com.kama.jchatmind.agent.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * Skill 定义，包含从 SKILL.md 解析的信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    /**
     * Skill 唯一标识
     */
    private String name;

    /**
     * Skill 描述
     */
    private String description;

    /**
     * SKILL.md 文件路径
     */
    private Path skillPath;

    /**
     * Skill 所在目录
     */
    private Path skillDirectory;

    /**
     * 原始 FrontMatter 内容
     */
    private String frontMatter;

    /**
     * 解析后的 Instructions 内容
     */
    private String instructions;

    /**
     * 原始 SKILL.md 内容（完整）
     */
    private String rawContent;

    /**
     * 最后修改时间
     */
    private long lastModified;
}