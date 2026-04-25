package com.kama.jchatmind.search;

import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;

public interface SearchProvider {
    SearchResult search(SearchRequest request);

    boolean isAvailable();

    String name();
}
