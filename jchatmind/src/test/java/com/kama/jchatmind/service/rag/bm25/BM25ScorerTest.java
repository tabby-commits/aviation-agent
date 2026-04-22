package com.kama.jchatmind.service.rag.bm25;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BM25ScorerTest {

    @Test
    void zeroInputsReturnZero() {
        assertEquals(0.0, BM25Scorer.termContribution(0, 1, 10, 5, 5.0, BM25Params.DEFAULT));
        assertEquals(0.0, BM25Scorer.termContribution(1, 0, 10, 5, 5.0, BM25Params.DEFAULT));
        assertEquals(0.0, BM25Scorer.termContribution(1, 1, 0, 5, 5.0, BM25Params.DEFAULT));
    }

    @Test
    void higherTfProducesHigherScoreButWithSaturation() {
        double s1 = BM25Scorer.termContribution(1, 5, 100, 50, 50, BM25Params.DEFAULT);
        double s5 = BM25Scorer.termContribution(5, 5, 100, 50, 50, BM25Params.DEFAULT);
        double s10 = BM25Scorer.termContribution(10, 5, 100, 50, 50, BM25Params.DEFAULT);
        assertTrue(s5 > s1, "tf=5 should score higher than tf=1");
        assertTrue(s10 > s5, "tf=10 should score higher than tf=5");
        // Saturation: the marginal return diminishes as tf grows.
        assertTrue((s10 - s5) < (s5 - s1) * 5, "BM25 should exhibit tf saturation");
    }

    @Test
    void rareTermScoresHigherThanCommon() {
        double rare = BM25Scorer.termContribution(1, 2, 1000, 50, 50, BM25Params.DEFAULT);
        double common = BM25Scorer.termContribution(1, 900, 1000, 50, 50, BM25Params.DEFAULT);
        assertTrue(rare > common, "rare term should score higher");
    }

    @Test
    void longerDocGetsPenalizedWhenBGreaterThanZero() {
        BM25Params params = new BM25Params(1.2, 0.75);
        double shortDoc = BM25Scorer.termContribution(1, 10, 1000, 10, 100, params);
        double longDoc = BM25Scorer.termContribution(1, 10, 1000, 500, 100, params);
        assertTrue(shortDoc > longDoc, "longer docs should be penalized by length normalization");
    }
}
