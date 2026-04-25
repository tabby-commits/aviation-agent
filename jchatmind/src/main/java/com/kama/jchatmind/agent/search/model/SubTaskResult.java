package com.kama.jchatmind.agent.search.model;

import java.util.List;

/**
 * 单个子任务的成功执行结果。
 * 由子 Agent 以严格 JSON 格式输出，SubAgentExecutionService 解析后填充此记录。
 *
 * @param taskId      与 SubTaskSpec.taskId 对应
 * @param status      固定值 "OK"；失败时不产生此记录，而是产生 SubTaskFailure
 * @param summary     子 Agent 对本任务的综合摘要
 * @param keyFindings 结构化关键发现列表
 * @param citations   本任务引用的所有来源
 * @param stats       执行统计
 */
public record SubTaskResult(
        String taskId,
        String status,
        String summary,
        List<KeyFinding> keyFindings,
        List<Citation> citations,
        SubTaskStats stats
) {
}
