package com.kama.jchatmind.service.rag.bm25;

/**
 * BM25 scoring parameters.
 * <p>
 * - k1: term-frequency saturation factor. Typical 1.2.
 * - b: document-length normalization strength. Typical 0.75.
 */
public class BM25Params {

    public static final BM25Params DEFAULT = new BM25Params(1.2, 0.75);

    private final double k1;
    private final double b;

    public BM25Params(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    public double getK1() {
        return k1;
    }

    public double getB() {
        return b;
    }
}
