package com.kama.jchatmind.agent.search.model;

/**
 * 单个子任务的搜索策略，对应 SubTaskSpec.searchPolicy。
 *
 * @param allowKbSearch    是否允许访问知识库（SubAgentKnowledgeTool）
 * @param allowWebSearch   是否允许执行外部 Web 搜索（WebSearchTool → Tavily）
 * @param maxSubSteps      子 Agent 的最大推理步数；受配置 maxSubStepsCap 硬上限夹住
 * @param timeoutSeconds   单任务超时秒数；超时则返回 SEARCH_DELEGATION_TIMEOUT
 */
public record SearchPolicy(
        boolean allowKbSearch,
        boolean allowWebSearch,
        int maxSubSteps,
        int timeoutSeconds
) {
}
