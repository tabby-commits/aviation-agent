package com.kama.jchatmind.search.model;

public record SearchHit(
        String title,
        String url,
        String snippet,
        String publishedAt,
        Double score,
        String source
) {
}
