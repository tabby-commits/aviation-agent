package com.kama.jchatmind.service.paper;

import java.util.Locale;
import java.util.Set;

/**
 * 论文章节标题过滤器（规则式，配合按页解析的章节状态机）
 * 用户决策：背景/综述类章节不做嵌入，只保留方法/模型/实验/结果等核心章节
 * 标题识别：行首允许编号前缀（1. / 1.2 / II. / A.），标题词精确或前缀匹配，行总长受限防止误报正文行
 */
public final class PaperSectionFilter {

    /** 排除章节：背景、综述、引言、结论、参考文献、致谢 */
    private static final Set<String> EXCLUDED_HEADINGS = Set.of(
            "introduction", "related work", "related works", "related studies",
            "background", "literature review", "literature survey", "review of",
            "preliminaries", "preliminary", "conclusion", "conclusions",
            "concluding remarks", "future work", "references", "reference",
            "bibliography", "acknowledgments", "acknowledgements", "acknowledgment",
            "acknowledgement", "appendix a references");

    /** 保留章节：摘要、模型、方法、实验、结果、性能、仿真、分析、讨论等 */
    private static final Set<String> KEPT_HEADINGS = Set.of(
            "abstract", "index terms", "keywords", "keyword",
            "system model", "system models", "system description", "system design",
            "method", "methods", "methodology", "proposed", "proposed scheme",
            "proposed method", "proposed approach", "proposed solution", "our approach",
            "experiment", "experiments", "experimental", "experimental setup",
            "experimental results", "experimental evaluation", "evaluation",
            "evaluation metrics", "performance evaluation", "performance analysis",
            "performance", "results", "result", "simulation", "simulation setup",
            "simulation results", "numerical results", "analysis", "implementation",
            "setup", "discussion", "design", "materials", "problem formulation",
            "problem statement", "overview", "motivation", "scenario", "use case");

    /** 标题行最大长度（超出视为正文句，防止 "Background interference is..." 误报） */
    private static final int MAX_HEADING_LINE_CHARS = 60;

    private PaperSectionFilter() {
    }

    public enum SectionState {
        KEPT, EXCLUDED
    }

    /**
     * 判断一行是否为章节标题行
     *
     * @return null=非标题行；KEPT/EXCLUDED=标题对应的章节状态
     */
    public static SectionState classifyHeading(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty() || line.length() > MAX_HEADING_LINE_CHARS) {
            return null;
        }
        // 去编号前缀："1."、"1.2"、"II."、"A."（罗马数字/单字母仅当后接点号或空格+大写标题）
        String stripped = line.replaceFirst(
                "^(\\d+(\\.\\d+)*|[IVXLC]{1,5}|[A-Z])\\.\\s+|^(\\d+(\\.\\d+)*|[IVXLC]{1,5})\\s+", "");
        if (stripped.isEmpty()) {
            return null;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);

        for (String heading : EXCLUDED_HEADINGS) {
            if (matchesHeading(lower, heading)) {
                return SectionState.EXCLUDED;
            }
        }
        for (String heading : KEPT_HEADINGS) {
            if (matchesHeading(lower, heading)) {
                return SectionState.KEPT;
            }
        }
        return null;
    }

    /** 标题词精确匹配或作为开头词（"introduction and background" 命中 introduction） */
    private static boolean matchesHeading(String lower, String heading) {
        return lower.equals(heading) || lower.startsWith(heading + " ");
    }
}
