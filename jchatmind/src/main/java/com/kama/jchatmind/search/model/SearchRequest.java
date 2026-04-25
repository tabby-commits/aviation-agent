package com.kama.jchatmind.search.model;

import java.util.List;

public record SearchRequest(
        String query,
        int count,
        String timeRange,
        List<String> domainFilter
) {
}
