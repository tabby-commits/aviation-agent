package com.kama.jchatmind.agent.search.model;

import java.util.List;

/**
 * 子任务的检索范围约束，对应 SubTaskSpec.scope。
 *
 * @param timeRange  时间范围，如 "2025" 或 "2025-01~2025-12"
 * @param region     地区限定，如 "CN" / "US"；null 表示不限
 * @param mustCover  必须覆盖的关键维度列表；空列表表示无强制要求
 */
public record SearchScope(
        String timeRange,
        String region,
        List<String> mustCover
) {
}
