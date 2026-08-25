package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 论文详情响应（全字段）
 */
@Data
@Builder
public class GetPaperResponse {
    private String docId;
    private String sourceDb;
    private String title;
    private String abstractText;
    private String authors;
    private String affiliations;
    private String firstAuthor;
    private String firstAuthorAffiliation;
    private String firstAuthorCountry;
    private String countryEvidence;
    private String countryConfidence;
    private Integer publishYear;
    private String journal;
    private String docType;
    private Integer citedCount;
    private String doi;
    private String keywords;
    private String screeningStatus;
    private String excludeReason;
    private String fileName;
}
