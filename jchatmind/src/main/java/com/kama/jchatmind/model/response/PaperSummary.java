package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 论文列表条目（分页查询返回）
 */
@Data
@Builder
public class PaperSummary {
    private String docId;
    private String sourceDb;
    private String title;
    private String firstAuthor;
    private String firstAuthorCountry;
    private Integer publishYear;
    private String journal;
    private String docType;
    private Integer citedCount;
    private String screeningStatus;
}
