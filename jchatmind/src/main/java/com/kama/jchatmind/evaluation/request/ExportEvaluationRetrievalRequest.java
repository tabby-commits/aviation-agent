package com.kama.jchatmind.evaluation.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportEvaluationRetrievalRequest {
    private String kbId;
    private String datasetFilename;
    private String outputFilename;
    private Integer topN;
}
