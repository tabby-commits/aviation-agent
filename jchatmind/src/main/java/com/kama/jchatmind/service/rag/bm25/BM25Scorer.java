package com.kama.jchatmind.service.rag.bm25;

/**
 * Pure-function BM25 scoring formula, extracted for unit testing.
 */
public final class BM25Scorer {

    private BM25Scorer() {}

    /**
     * Contribution of a single term to a document's BM25 score.
     *
     * @param tf        term frequency in this document
     * @param df        document frequency of the term
     * @param totalDocs total number of documents in the collection
     * @param docLen    token length of this document
     * @param avgDocLen average token length across the collection
     * @param params    BM25 parameters
     */
    public static double termContribution(int tf,
                                          int df,
                                          int totalDocs,
                                          int docLen,
                                          double avgDocLen,
                                          BM25Params params) {
        if (tf <= 0 || df <= 0 || totalDocs <= 0) {
            return 0.0;
        }
        double k1 = params.getK1();
        double b = params.getB();
        double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
        double avg = avgDocLen <= 0 ? 1.0 : avgDocLen;
        double norm = tf * (k1 + 1.0)
                / (tf + k1 * (1.0 - b + b * (docLen / avg)));
        return idf * norm;
    }
}
