package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 分类体系节点响应（GET /api/taxonomy）
 */
@Data
@Builder
public class TaxonomyNodeResponse {
    private String code;
    private String parentCode;
    private String nameEn;
    private String nameCn;
    private String description;
    private Integer level;
    /** 该分类下已归属论文数（实时聚合） */
    private long paperCount;
}
