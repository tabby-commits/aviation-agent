package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 分类体系节点实体（对应表 taxonomy_node）
 * 体系设计见 docs/superpowers/plans/2026-08-26-step2-import-plan.md（1 层 6 类）
 */
@Data
@Builder
public class TaxonomyNode {
    private String id;

    /** 稳定标识，如 space-computing */
    private String code;

    private String parentCode;

    /** 英文名 = EASC 一级类目原名（保证论文归属映射 1:1） */
    private String nameEn;

    private String nameCn;

    private String description;

    private Integer level;

    private LocalDateTime createdAt;
}
