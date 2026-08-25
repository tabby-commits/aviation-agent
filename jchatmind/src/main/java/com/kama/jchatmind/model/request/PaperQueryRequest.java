package com.kama.jchatmind.model.request;

import lombok.Data;

/**
 * 论文分页查询请求（GET /api/papers 与 Mapper 条件查询共用）
 */
@Data
public class PaperQueryRequest {

    private String sourceDb;

    private String country;

    private Integer yearFrom;

    private Integer yearTo;

    private String screeningStatus;

    /** 标题/摘要关键词（ILIKE 模糊匹配） */
    private String keyword;

    private int page = 1;

    private int pageSize = 20;

    public int getOffset() {
        return Math.max(0, (page - 1) * pageSize);
    }
}
