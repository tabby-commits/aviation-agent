package com.kama.jchatmind.evaluation.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportEvaluationRetrievalResponse {
    private String kbId;
    private String datasetFilename;
    private String outputFilename;
    private String outputPath;
    private int totalSamples;
    private int exportedSamples;
    private int topN;
    private String message;
}
