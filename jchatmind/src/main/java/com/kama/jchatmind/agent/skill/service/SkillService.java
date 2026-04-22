package com.kama.jchatmind.agent.skill.service;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Skill 服务接口
 */
public interface SkillService {

    /**
     * 获取所有已加载的 Skill
     */
    List<SkillDefinition> getAllSkills();

    /**
     * 根据名称获取 Skill
     */
    Optional<SkillDefinition> getSkillByName(String name);

    /**
     * 调用 Skill，返回其内容
     *
     * @param skillName Skill 名称
     * @param context   调用上下文（可选）
     * @return Skill 的完整内容（用于 AI 执行）
     */
    String invokeSkill(String skillName, String context);

    /**
     * 重新加载所有 Skill
     */
    void reload();

    /**
     * 获取指定 Skill 的目录路径
     */
    String getSkillDirectory(String skillName);
}