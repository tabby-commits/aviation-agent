package com.kama.jchatmind.model.response;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 分类节点下的论文列表与国别分布（GET /api/taxonomy/{code}/papers）
 */
@Data
@Builder
public class TaxonomyPapersResponse {
    private String code;
    private String nameCn;
    private long total;
    /** key=国别（CN/US/null→未知），value=论文数 */
    private Map<String, Long> byCountry;
    private List<PaperBrief> papers;

    @Data
    @Builder
    public static class PaperBrief {
        private String docId;
        private String title;
        private String country;
        private Integer publishYear;
    }
}
