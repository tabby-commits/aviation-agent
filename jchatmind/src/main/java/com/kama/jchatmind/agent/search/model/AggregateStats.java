package com.kama.jchatmind.agent.search.model;

/**
 * 本次委派的全局聚合统计，包含总耗时与 Token 消耗。
 *
 * @param elapsedMs       从提交第一个子任务到所有任务完成（或超时）的实际耗时（毫秒）
 * @param totalTokenUsage 所有子任务的 Token 消耗之和；各模型计量标准可能不同，仅供参考
 */
public record AggregateStats(
        long elapsedMs,
        TokenUsage totalTokenUsage
) {
}
