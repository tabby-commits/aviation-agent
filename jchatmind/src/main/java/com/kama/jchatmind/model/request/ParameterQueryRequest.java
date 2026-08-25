package com.kama.jchatmind.model.request;

import lombok.Data;

/**
 * 参数证据分页查询请求（GET /api/papers 的参数版）
 */
@Data
public class ParameterQueryRequest {

    /** 规范参数族（如 时延/延迟） */
    private String family;

    /** 第一作者国别（CN/US） */
    private String country;

    /** 技术对象/参数名/证据原文关键词（ILIKE） */
    private String keyword;

    private String docId;

    private int page = 1;

    private int pageSize = 20;

    public int getOffset() {
        return Math.max(0, (page - 1) * pageSize);
    }
}
