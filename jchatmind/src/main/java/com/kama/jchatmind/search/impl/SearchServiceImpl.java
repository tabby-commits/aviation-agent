package com.kama.jchatmind.search.impl;

import com.kama.jchatmind.search.SearchProvider;
import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class SearchServiceImpl implements SearchService {

    private final List<SearchProvider> providers;

    public SearchServiceImpl(List<SearchProvider> providers) {
        this.providers = providers;
    }

    @Override
    public SearchResult search(SearchRequest request) {
        SearchProvider provider = primaryProvider();
        if (provider == null || !provider.isAvailable()) {
            return SearchResult.empty(request.query(), provider == null ? "none" : provider.name(), true, "search provider unavailable");
        }

        try {
            return provider.search(request);
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

}
