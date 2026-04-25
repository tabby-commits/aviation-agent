package com.kama.jchatmind.search.model;

import java.util.List;

public record SearchResult(
        String query,
        List<SearchHit> hits,
        String provider,
        boolean degraded,
        String errorMessage
) {
    public static SearchResult empty(String query, String provider, boolean degraded, String errorMessage) {
        return new SearchResult(query, List.of(), provider, degraded, errorMessage);
    }
}
