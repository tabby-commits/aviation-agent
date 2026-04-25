package com.kama.jchatmind.search.impl;

import com.kama.jchatmind.search.SearchProvider;
import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SearchServiceImpl implements SearchService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final List<SearchProvider> providers;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SearchServiceImpl(List<SearchProvider> providers) {
        this.providers = providers;
    }

    @Override
    public SearchResult search(SearchRequest request) {
        SearchProvider provider = primaryProvider();
        if (provider == null || !provider.isAvailable()) {
            return SearchResult.empty(request.query(), provider == null ? "none" : provider.name(), true, "search provider unavailable");
        }

        String key = cacheKey(request, provider.name());
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.expired()) {
            return entry.result();
        }

        try {
            SearchResult result = provider.search(request);
            cache.put(key, new CacheEntry(result, Instant.now().plus(CACHE_TTL)));
            return result;
        } catch (Exception e) {
            return SearchResult.empty(request.query(), provider.name(), true, e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        SearchProvider provider = primaryProvider();
        return provider != null && provider.isAvailable();
    }

    private SearchProvider primaryProvider() {
        return providers.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String cacheKey(SearchRequest request, String provider) {
        return provider + "|" + request.query() + "|" + request.count() + "|" + request.timeRange() + "|" + request.domainFilter();
    }

    private record CacheEntry(SearchResult result, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
