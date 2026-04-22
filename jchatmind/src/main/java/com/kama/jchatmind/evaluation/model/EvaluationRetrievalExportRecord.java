package com.kama.jchatmind.evaluation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRetrievalExportRecord {
    @JsonProperty("sample_id")
    private String sampleId;

    @JsonProperty("kb_id")
    private String kbId;

    @JsonProperty("user_input")
    private String userInput;

    private String reference;

    @JsonProperty("reference_context_ids")
    private List<String> referenceContextIds;

    @JsonProperty("reference_contexts")
    private List<String> referenceContexts;

    private String topic;

    private String difficulty;

    @JsonProperty("top_n")
    private int topN;

    private List<RetrievalHit> retrieved;
}
