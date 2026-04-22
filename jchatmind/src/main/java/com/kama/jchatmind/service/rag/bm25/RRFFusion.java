package com.kama.jchatmind.service.rag.bm25;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion utility.
 * <p>
 * For each ranked id list, add {@code 1.0 / (k + r)} to every id's score
 * (where r is 1-based rank). Return the top N ids by summed score.
 */
public final class RRFFusion {

    private RRFFusion() {}

    /**
     * Fuse multiple ranked id lists.
     *
     * @param k             RRF constant, typically 60
     * @param topN          number of ids to return
     * @param rankedIdLists the ranked lists to fuse
     */
    public static List<String> fuse(int k, int topN, List<List<String>> rankedIdLists) {
        if (rankedIdLists == null || rankedIdLists.isEmpty() || topN <= 0) {
            return Collections.emptyList();
        }
        int rrfK = Math.max(1, k);
        Map<String, Double> scoreMap = new HashMap<>();
        for (List<String> list : rankedIdLists) {
            if (list == null) continue;
            int rank = 1;
            for (String id : list) {
                if (id == null) continue;
                scoreMap.merge(id, 1.0 / (rrfK + rank), Double::sum);
                rank++;
            }
        }
        if (scoreMap.isEmpty()) return Collections.emptyList();
        return scoreMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }
}
