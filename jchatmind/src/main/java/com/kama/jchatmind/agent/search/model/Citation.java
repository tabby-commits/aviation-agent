package com.kama.jchatmind.agent.search.model;

/**
 * 单条引用来源，可来自 Web 搜索或知识库 Chunk。
 * sourceType="web" 时 url 有效，docId/chunkId 为 null；
 * sourceType="kb"  时 docId/chunkId 有效，url 为 null。
 *
 * @param id          引用唯一标识，格式 "web:N" 或 "kb:N"，与 KeyFinding.citations 对应
 * @param title       来源标题
 * @param url         Web 来源 URL；KB 来源时为 null
 * @param docId       知识库文档 ID；Web 来源时为 null
 * @param chunkId     知识库 Chunk ID；Web 来源时为 null
 * @param snippet     摘录文本片段
 * @param sourceType  来源类型："web" 或 "kb"
 * @param publishedAt 发布时间（ISO-8601 日期字符串）；KB 来源或无法获取时为 null
 */
public record Citation(
        String id,
        String title,
        String url,
        String docId,
        String chunkId,
        String snippet,
        String sourceType,
        String publishedAt
) {
}
