package com.kama.jchatmind.evaluation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluationQuerySample {
    @JsonProperty("sample_id")
    private String sampleId;

    @JsonProperty("user_input")
    private String userInput;

    private String reference;

    @JsonProperty("reference_context_ids")
    private List<String> referenceContextIds;

    @JsonProperty("reference_contexts")
    private List<String> referenceContexts;

    private String topic;

    private String difficulty;
}
