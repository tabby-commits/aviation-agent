package com.kama.jchatmind.agent.skill.scanner;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.parser.SkillMdParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill 目录扫描器
 * 负责从指定目录扫描所有 SKILL.md 文件
 */
@Slf4j
@Component
public class SkillScanner {

    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final SkillMdParser parser;

    public SkillScanner(SkillMdParser parser) {
        this.parser = parser;
    }

    /**
     * 扫描指定目录，查找所有 SKILL.md 文件
     *
     * @param directory 目录路径
     * @return 扫描到的 Skill 定义列表
     */
    public List<SkillDefinition> scan(Path directory) {
        if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
            log.warn("Skill 扫描目录不存在或不是有效目录: {}", directory);
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> SKILL_FILE_NAME.equals(path.getFileName().toString()))
                    .map(this::parseSkillFile)
                    .filter(skill -> skill != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("扫描 Skill 目录失败: {}", directory, e);
            return List.of();
        }
    }

    /**
     * 扫描多个目录
     *
     * @param directories 目录列表
     * @return 所有扫描到的 Skill 定义
     */
    public List<SkillDefinition> scanAll(List<Path> directories) {
        if (directories == null || directories.isEmpty()) {
            return List.of();
        }

        return directories.stream()
                .map(this::scan)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个 SKILL.md 文件
     */
    private SkillDefinition parseSkillFile(Path skillPath) {
        try {
            return parser.parse(skillPath);
        } catch (Exception e) {
            log.error("解析 Skill 文件失败: {}", skillPath, e);
            return null;
        }
    }
}