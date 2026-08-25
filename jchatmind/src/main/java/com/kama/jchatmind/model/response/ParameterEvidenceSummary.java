package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 参数证据查询条目（GET /api/parameters）
 */
@Data
@Builder
public class ParameterEvidenceSummary {
    private String decisionId;
    private String docId;
    private String title;
    private String firstAuthorCountry;
    private Integer publishYear;
    private String technicalObject;
    private String parameterNameCanonical;
    private String parameterFamily;
    private String valueRaw;
    private Double valueMin;
    private Double valueMax;
    private String unitNormalized;
    private String conditionText;
    private String evidenceText;
    private Integer pageNumber;
    private String section;
    private String sourceType;
    private String reviewStatus;
}
