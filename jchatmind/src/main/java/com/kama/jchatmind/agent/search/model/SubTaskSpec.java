package com.kama.jchatmind.agent.search.model;

/**
 * 主 Agent 委派给子 Agent 的单个检索任务描述。
 * 作为 {@code delegateSearchTask} 工具的入参列表元素。
 *
 * @param taskId          任务唯一标识，由主 Agent 自行分配，用于对齐结果
 * @param taskDescription 子 Agent 的自然语言任务描述，须清晰、可独立执行
 * @param kbId            目标知识库 ID；allowKbSearch=false 时可为 null
 * @param scope           检索范围约束（时间、地区、必覆盖维度）；可为 null
 * @param searchPolicy    子 Agent 的工具权限与步数/超时限制
 */
public record SubTaskSpec(
        String taskId,
        String taskDescription,
        String kbId,
        SearchScope scope,
        SearchPolicy searchPolicy
) {
}
