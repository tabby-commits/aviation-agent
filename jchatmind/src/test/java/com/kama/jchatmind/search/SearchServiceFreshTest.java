package com.kama.jchatmind.search;

import com.kama.jchatmind.search.impl.SearchServiceImpl;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SearchServiceFreshTest {
    @Test
    void sameQueryCallsProviderOnEveryRequest() {
        SearchProvider provider = mock(SearchProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.name()).thenReturn("test");
        SearchRequest request = new SearchRequest("satellite progress", 4, null, List.of());
        when(provider.search(request)).thenReturn(SearchResult.empty("satellite progress", "test", false, null));
        SearchServiceImpl service = new SearchServiceImpl(List.of(provider));

        service.search(request);
        service.search(request);

        verify(provider, times(2)).search(request);
        assertEquals("test", service.search(request).provider());
        verify(provider, times(3)).search(request);
    }
}
