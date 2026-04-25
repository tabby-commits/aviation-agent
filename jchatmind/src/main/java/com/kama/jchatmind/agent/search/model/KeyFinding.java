package com.kama.jchatmind.agent.search.model;

import java.util.List;

/**
 * 子任务的单条关键发现，包含标题、详情描述及支撑引用。
 *
 * @param title     发现的简短标题
 * @param detail    详情描述；若含数字，必须对应 citations 中的具体来源
 * @param citations 支撑本条发现的引用 ID 列表（对应 SubTaskResult.citations[].id）
 */
public record KeyFinding(
        String title,
        String detail,
        List<String> citations
) {
}
