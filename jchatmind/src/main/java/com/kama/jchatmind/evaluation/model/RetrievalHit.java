package com.kama.jchatmind.evaluation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalHit {
    @JsonProperty("chunk_id")
    private String chunkId;

    @JsonProperty("doc_id")
    private String docId;

    private String content;

    private Integer rank;

    @JsonProperty("retrieval_source")
    private String retrievalSource;

    @JsonProperty("vector_rank")
    private Integer vectorRank;

    @JsonProperty("vector_distance")
    private Double vectorDistance;

    @JsonProperty("bm25_rank")
    private Integer bm25Rank;

    @JsonProperty("bm25_score")
    private Double bm25Score;

    @JsonProperty("is_final_top_k")
    private boolean finalTopK;
}
