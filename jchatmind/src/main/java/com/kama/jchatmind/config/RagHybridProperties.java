package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Properties for hybrid retrieval (vector + BM25 with RRF fusion).
 */
@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.rag.hybrid")
public class RagHybridProperties {

    /**
     * Whether hybrid retrieval is enabled. When false, falls back to pure vector search.
     */
    private boolean enabled = true;

    /**
     * Top-K1 candidates to fetch from the vector channel.
     */
    private int vectorTopK = 20;

    /**
     * Top-K2 candidates to fetch from the BM25 channel.
     */
    private int bm25TopK = 20;

    /**
     * Final Top-N returned after RRF fusion.
     */
    private int finalTopN = 3;

    /**
     * RRF constant k. Default 60 is a widely-adopted value.
     */
    private int rrfK = 60;

    /**
     * If true, warm up all knowledge-base indices on application ready (not implemented yet).
     */
    private boolean warmupOnStartup = false;

    /**
     * Hard upper limit for topN passed to hybridSearch().
     * Prevents sub-agents from requesting unreasonably large result sets.
     */
    private int maxTopN = 100;

    private Bm25 bm25 = new Bm25();

    @Data
    public static class Bm25 {
        private double k1 = 1.2;
        private double b = 0.75;
    }
}
