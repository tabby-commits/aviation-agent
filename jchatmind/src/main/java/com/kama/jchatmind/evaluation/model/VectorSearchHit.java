package com.kama.jchatmind.evaluation.model;

import lombok.Data;

@Data
public class VectorSearchHit {
    private String id;
    private String kbId;
    private String docId;
    private String content;
    private String metadata;
    private Double vectorDistance;
}
