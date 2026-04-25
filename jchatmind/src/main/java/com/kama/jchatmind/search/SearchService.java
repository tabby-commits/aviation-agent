package com.kama.jchatmind.search;

import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;

public interface SearchService {
    SearchResult search(SearchRequest request);

    boolean isAvailable();
}
