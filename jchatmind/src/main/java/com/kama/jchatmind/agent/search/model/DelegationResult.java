package com.kama.jchatmind.agent.search.model;

import java.util.List;

/**
 * {@code delegateSearchTask} 工具的完整出参。
 * 主 Agent 收到此对象后应：
 * <ol>
 *   <li>利用 results 中的 summary + keyFindings + citations 综合成文；</li>
 *   <li>对 failures 中的任务，在综述里降级说明"某维度材料不足"，不得编造。</li>
 * </ol>
 *
 * @param results   成功完成的子任务结果列表
 * @param failures  失败的子任务列表（可为空列表，不为 null）
 * @param aggregate 本次委派的全局聚合统计
 * @param stopRetryHint 非空时提示主 Agent 勿重复调用 delegateSearchTask，仅基于当前结果输出
 */
public record DelegationResult(
        List<SubTaskResult> results,
        List<SubTaskFailure> failures,
        AggregateStats aggregate,
        String stopRetryHint
) {
    public DelegationResult(List<SubTaskResult> results, List<SubTaskFailure> failures, AggregateStats aggregate) {
        this(results, failures, aggregate, null);
    }
}
