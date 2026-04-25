package com.kama.jchatmind.agent.search.model;

/**
 * 本次委派的全局并发与超时策略。
 * 与 {@link SubTaskSpec#searchPolicy()} 的区别：GlobalPolicy 约束整批子任务，
 * SearchPolicy 约束单个子任务。
 *
 * @param maxParallel           本次委派内最多同时运行的子任务数；受配置 delegation.max-parallel 上限夹住
 * @param globalTimeoutSeconds  所有子任务必须在此秒数内全部完成；超时则强制聚合已完成部分
 */
public record GlobalPolicy(
        int maxParallel,
        int globalTimeoutSeconds
) {
}
