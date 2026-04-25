package com.kama.jchatmind.agent.search.model;

/**
 * Token 消耗统计，用于 DelegationResult.aggregate。
 *
 * @param prompt     本次委派所有子任务消耗的 prompt token 总数
 * @param completion 本次委派所有子任务消耗的 completion token 总数
 */
public record TokenUsage(
        int prompt,
        int completion
) {
}
