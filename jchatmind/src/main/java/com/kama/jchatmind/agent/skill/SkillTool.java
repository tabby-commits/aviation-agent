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
            航天竞争力分析使用三个专用 Skill，按问题类型选择：
            - competitiveness-framework：分析中美航天科技竞争力、选择竞争力指标、分解比较问题时加载（28项指标框架）
            - data-resource-rules：检索或引用论文/参数证据/新闻/外部信息时加载（数据源选择、优先级与引用格式）
            - ci-analysis-methods：组织竞争力分析的方法选择、比较口径核对、事实与推断区分时加载（五类分析方法）
            开始航天竞争力分析前必须先加载 competitiveness-framework；检索证据前加载 data-resource-rules。
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
