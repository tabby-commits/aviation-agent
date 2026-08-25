package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 论文—分类归属实体（对应表 paper_taxonomy）
 * membership_type：assigned（正式归属）；source：归属来源（如 easc_v19_level2）
 */
@Data
@Builder
public class PaperTaxonomy {
    private String id;

    private String docId;

    private String taxonomyCode;

    private String membershipType;

    private String source;

    private LocalDateTime createdAt;
}
