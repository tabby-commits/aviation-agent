package com.kama.jchatmind.evaluation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredRetrievalResult {
    private String kbId;
    private String query;
    private int topN;
    private int vectorK;
    private int bm25K;
    private List<RetrievalHit> hits;
}
