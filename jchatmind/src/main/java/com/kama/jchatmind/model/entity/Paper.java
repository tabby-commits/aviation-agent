package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 论文元数据实体（对应表 paper）
 * doc_id 规则：WoS 为 UT 值（如 WOS:000388603100003）；CNKI 为构造 ID（CNKI: + md5(title|journal|year) 的 hex）
 * authors / affiliations 存 JSON 数组字符串，Java 侧不拆解
 */
@Data
@Builder
public class Paper {
    private String id;

    private String docId;

    /** 来源库：WOS / CNKI */
    private String sourceDb;

    private String title;

    private String abstractText;

    /** JSON 数组字符串，如 ["Author, A","Author, B"] */
    private String authors;

    /** JSON 数组字符串，机构地址列表 */
    private String affiliations;

    private String firstAuthor;

    private String firstAuthorAffiliation;

    /** 第一作者国别（CN/US/...），由筛选结论导入填充，元数据导入时为空 */
    private String firstAuthorCountry;

    private String countryEvidence;

    private String countryConfidence;

    private Integer publishYear;

    private String journal;

    private String docType;

    private Integer citedCount;

    private String doi;

    /** 关键词，保留源分隔符原样字符串 */
    private String keywords;

    /** pending / included / excluded */
    private String screeningStatus;

    private String excludeReason;

    private String fileName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
