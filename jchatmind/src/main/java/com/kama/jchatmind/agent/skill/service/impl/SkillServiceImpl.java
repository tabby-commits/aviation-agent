package com.kama.jchatmind.agent.skill.service.impl;

import com.kama.jchatmind.agent.skill.config.SkillProperties;
import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.scanner.SkillScanner;
import com.kama.jchatmind.agent.skill.service.SkillService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill 服务实现
 */
@Slf4j
@Service
public class SkillServiceImpl implements SkillService {

    /**
     * 内存中的 Skill 缓存
     */
    private final Map<String, SkillDefinition> skillCache = new ConcurrentHashMap<>();

    private final SkillScanner scanner;
    private final SkillProperties properties;

    public SkillServiceImpl(SkillScanner scanner, SkillProperties properties) {
        this.scanner = scanner;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (properties.isEnabled()) {
            reload();
        }
    }

    @Override
    public List<SkillDefinition> getAllSkills() {
        return List.copyOf(skillCache.values());
    }

    @Override
    public Optional<SkillDefinition> getSkillByName(String name) {
        return Optional.ofNullable(skillCache.get(name));
    }

    @Override
    public String invokeSkill(String skillName, String context) {
        SkillDefinition skill = skillCache.get(skillName);
        if (skill == null) {
            return "Error: Skill not found: " + skillName;
        }

        return buildSkillResponse(skill, context);
    }

    /**
     * 构建 Skill 调用响应
     * 返回 Skill 的完整内容，供 AI 理解和执行
     */
    private String buildSkillResponse(SkillDefinition skill, String context) {
        StringBuilder response = new StringBuilder();
        response.append("# Skill: ").append(skill.getName()).append("\n\n");
        response.append("**Description:** ").append(skill.getDescription()).append("\n\n");
        response.append("**Location:** ").append(skill.getSkillDirectory()).append("\n\n");
        response.append("---\n\n");
        response.append(skill.getInstructions());

        if (context != null && !context.isEmpty()) {
            response.append("\n\n---\n\n**User Context:**\n").append(context);
        }

        return response.toString();
    }

    @Override
    public void reload() {
        log.info("开始重新加载 Skill...");
        skillCache.clear();

        List<String> directories = properties.getScanDirectories();
        if (directories == null || directories.isEmpty()) {
            log.warn("未配置 Skill 扫描目录");
            return;
        }

        List<Path> paths = directories.stream()
                .map(Path::of)
                .collect(Collectors.toList());

        List<SkillDefinition> allSkills = scanner.scanAll(paths);
        for (SkillDefinition skill : allSkills) {
            skillCache.put(skill.getName(), skill);
        }

        log.info("Skill 加载完成，共 {} 个", skillCache.size());
    }

    @Override
    public String getSkillDirectory(String skillName) {
        return Optional.ofNullable(skillCache.get(skillName))
                .map(s -> s.getSkillDirectory().toString())
                .orElse(null);
    }
}