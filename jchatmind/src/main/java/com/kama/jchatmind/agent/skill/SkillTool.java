package com.kama.jchatmind.agent.skill;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.service.SkillService;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Skill Tool - 将 Skill 功能封装为可被 AI 调用的 Tool
 * <p>
 * 这个类实现了 Tool 接口，会被自动注入到 ToolFacadeServiceImpl
 * 从而被 JChatMindFactory 收集并注册为可调用的工具
 */
@Slf4j
@Component
public class SkillTool implements Tool {

    private final SkillService skillService;

    public SkillTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String getName() {
        return "skillTool";
    }

    @Override
    public String getDescription() {
        List<String> skillNames = skillService.getAllSkills()
                .stream()
                .map(SkillDefinition::getName)
                .collect(Collectors.toList());

        if (skillNames.isEmpty()) {
            return "用于调用已注册的 Skill（专业能力包）。目前没有已加载的 Skill。";
        }

        return "用于调用已注册的 Skill（专业能力包）。" +
                "每个 Skill 包含特定领域的指令和知识，如代码审查、数据分析等。" +
                "参数 skillName 指定要调用的 Skill 名称。\n" +
                "可用 Skill: " + String.join(", ", skillNames);
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    /**
     * 调用指定的 Skill
     *
     * @param skillName Skill 名称
     * @param context   调用上下文（可选）
     * @return Skill 的完整内容
     */
    @org.springframework.ai.tool.annotation.Tool(name = "invokeSkill", description = """
            调用指定的 Skill（专业能力包），获取特定领域任务的详细分析指令。
            参数 skillName 为 Skill 名称，context 为用户问题或任务上下文。
            当问题涉及航天、商业航天、航天科技情报、科技竞争力、战略重要性、技术前瞻性、创新能力、产业成熟度或动态路径评估时，
            必须先调用本工具并传入 skillName="space-tech-intelligence-analyst"，context 传入用户原始问题，然后再开展分析。
            如果不确定应使用哪个 Skill，先调用 listSkills 查看可用 Skill。
            """)
    public String invokeSkill(String skillName, String context) {
        log.info("调用 Skill: {}, context: {}", skillName, context);
        return skillService.invokeSkill(skillName, context);
    }

    /**
     * 列出所有可用的 Skill
     *
     * @return 可用 Skill 列表
     */
    @org.springframework.ai.tool.annotation.Tool(name = "listSkills", description = "列出所有可用的 Skill（专业能力包）。返回所有已注册的 Skill 名称及其描述。")
    public String listSkills() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 可用 Skill 列表\n\n");

        List<SkillDefinition> skills = skillService.getAllSkills();
        if (skills.isEmpty()) {
            sb.append("暂无已加载的 Skill。\n");
            sb.append("请在配置文件中设置 jchatmind.skill.scan-directories 并添加 SKILL.md 文件。");
            return sb.toString();
        }

        for (SkillDefinition skill : skills) {
            sb.append("## ").append(skill.getName()).append("\n");
            sb.append(skill.getDescription()).append("\n\n");
        }

        return sb.toString();
    }
}
