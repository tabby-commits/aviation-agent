package com.kama.jchatmind.agent.skill.parser;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * SKILL.md 文件解析器
 * <p>
 * 解析格式:
 * ---
 * name: code-reviewer
 * description: Reviews Java code for best practices...
 * ---
 * # Code Reviewer
 * ## Instructions
 * When reviewing code:
 * ...
 */
@Slf4j
@Component
public class SkillMdParser {

    private static final String FRONTMATTER_DELIMITER = "---";
    private final Yaml yaml;

    public SkillMdParser() {
        this.yaml = new Yaml();
    }

    /**
     * 解析 SKILL.md 文件
     *
     * @param skillPath 文件路径
     * @return Skill 定义
     */
    public SkillDefinition parse(Path skillPath) {
        try {
            String content = Files.readString(skillPath, StandardCharsets.UTF_8);
            return parse(content, skillPath);
        } catch (Exception e) {
            log.error("读取 SKILL.md 文件失败: {}", skillPath, e);
            throw new RuntimeException("读取 SKILL.md 文件失败: " + skillPath, e);
        }
    }

    /**
     * 解析 SKILL.md 内容字符串
     *
     * @param content  文件内容
     * @param skillPath 文件路径
     * @return Skill 定义
     */
    public SkillDefinition parse(String content, Path skillPath) {
        // 提取 FrontMatter
        String frontMatter = extractFrontMatter(content);
        String instructions = extractInstructions(content);

        // 解析 YAML
        Map<String, String> metadata = parseFrontMatter(frontMatter);

        String name = metadata.getOrDefault("name", getDefaultName(skillPath));
        String description = metadata.getOrDefault("description", "");

        return SkillDefinition.builder()
                .name(name)
                .description(description)
                .skillPath(skillPath)
                .skillDirectory(skillPath.getParent())
                .frontMatter(frontMatter)
                .instructions(instructions)
                .rawContent(content)
                .lastModified(System.currentTimeMillis())
                .build();
    }

    /**
     * 提取 FrontMatter（--- 之间的内容）
     */
    private String extractFrontMatter(String content) {
        if (content == null || !content.startsWith(FRONTMATTER_DELIMITER)) {
            return "";
        }

        int start = FRONTMATTER_DELIMITER.length();
        int end = content.indexOf(FRONTMATTER_DELIMITER, start);

        if (end == -1) {
            return "";
        }

        return content.substring(start, end).trim();
    }

    /**
     * 提取 Instructions（FrontMatter 之后的所有内容）
     */
    private String extractInstructions(String content) {
        if (content == null) {
            return "";
        }

        // 找到第二个 --- 的位置
        int firstDelimiter = content.indexOf(FRONTMATTER_DELIMITER);
        if (firstDelimiter == -1) {
            return content.trim();
        }

        int secondDelimiter = content.indexOf(FRONTMATTER_DELIMITER, firstDelimiter + FRONTMATTER_DELIMITER.length());
        if (secondDelimiter == -1) {
            return content.trim();
        }

        // 跳过第二个 --- 后的换行，返回剩余内容
        return content.substring(secondDelimiter + FRONTMATTER_DELIMITER.length()).trim();
    }

    /**
     * 解析 FrontMatter YAML
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseFrontMatter(String frontMatter) {
        if (frontMatter == null || frontMatter.isEmpty()) {
            return Map.of();
        }

        try {
            Map<String, Object> raw = yaml.load(frontMatter);
            if (raw == null) {
                return Map.of();
            }

            // 将所有值转换为 String
            return raw.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue() == null ? "" : e.getValue().toString()
                    ));
        } catch (Exception e) {
            log.warn("解析 FrontMatter 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从路径获取默认名称
     */
    private String getDefaultName(Path skillPath) {
        if (skillPath == null || skillPath.getParent() == null) {
            return "unknown";
        }
        return skillPath.getParent().getFileName().toString();
    }
}