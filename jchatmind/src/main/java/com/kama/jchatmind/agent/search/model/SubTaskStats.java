package com.kama.jchatmind.agent.search.model;

/**
 * 子任务执行统计信息，供主 Agent 了解各子任务的资源消耗。
 *
 * @param toolCalls  子 Agent 本轮执行的总工具调用次数
 * @param kbQueries  命中 SubAgentKnowledgeTool 的次数
 * @param webQueries 命中 WebSearchTool 的次数
 * @param elapsedMs  子任务从启动到返回的耗时（毫秒）
 * @param fallback   若发生自动降级，此字段说明降级类型（如 "web_to_kb"）；正常执行时为 null
 */
public record SubTaskStats(
        int toolCalls,
        int kbQueries,
        int webQueries,
        long elapsedMs,
        String fallback
) {
}
