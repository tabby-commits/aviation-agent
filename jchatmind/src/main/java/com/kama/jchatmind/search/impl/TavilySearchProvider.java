package com.kama.jchatmind.search.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kama.jchatmind.config.TavilyProperties;
import com.kama.jchatmind.search.SearchProvider;
import com.kama.jchatmind.search.SearchProviderException;
import com.kama.jchatmind.search.model.SearchHit;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TavilySearchProvider implements SearchProvider {

    private final TavilyProperties properties;
    private final WebClient.Builder webClientBuilder;

    public TavilySearchProvider(TavilyProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public SearchResult search(SearchRequest request) {
        if (!isAvailable()) {
            throw new SearchProviderException("Tavily is disabled or api-key is empty");
        }

        Exception lastError = null;
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                TavilyResponse response = webClientBuilder
                        .baseUrl(properties.getBaseUrl())
                        .build()
                        .post()
                        .uri("/search")
                        .bodyValue(buildBody(request))
                        .retrieve()
                        .bodyToMono(TavilyResponse.class)
                        .block(Duration.ofMillis(properties.getTimeoutMs()));
                List<SearchHit> hits = response == null || response.getResults() == null
                        ? List.of()
                        : response.getResults().stream()
                        .map(this::toHit)
                        .toList();
                return new SearchResult(request.query(), hits, name(), false, null);
            } catch (Exception e) {
                lastError = e;
                sleepBeforeRetry(i);
            }
        }
        throw new SearchProviderException("Tavily search failed", lastError);
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled() && StringUtils.hasText(properties.getApiKey());
    }

    @Override
    public String name() {
        return "tavily";
    }

    private Map<String, Object> buildBody(SearchRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", properties.getApiKey());
        body.put("query", request.query());
        body.put("search_depth", properties.getSearchDepth());
        body.put("max_results", request.count() > 0 ? request.count() : properties.getDefaultCount());
        body.put("include_answer", false);
        body.put("include_raw_content", false);
        if (StringUtils.hasText(request.timeRange())) {
            applyTimeRange(body, request.timeRange());
        }
        if (request.domainFilter() != null && !request.domainFilter().isEmpty()) {
            body.put("include_domains", request.domainFilter());
        }
        return body;
    }

    private void applyTimeRange(Map<String, Object> body, String timeRange) {
        if (timeRange.matches("\\d{4}")) {
            body.put("start_date", timeRange + "-01-01");
            body.put("end_date", timeRange + "-12-31");
            return;
        }
        if (timeRange.matches("\\d{4}-\\d{2}~\\d{4}-\\d{2}")) {
            String[] parts = timeRange.split("~", 2);
            body.put("start_date", parts[0] + "-01");
            body.put("end_date", parts[1] + "-31");
            return;
        }
        body.put("time_range", timeRange);
    }

    private SearchHit toHit(TavilyHit hit) {
        return new SearchHit(
                hit.getTitle(),
                hit.getUrl(),
                hit.getContent(),
                hit.getPublishedDate(),
                hit.getScore(),
                name()
        );
    }

    private void sleepBeforeRetry(int attempt) {
        if (attempt >= properties.getMaxRetries()) {
            return;
        }
        try {
            Thread.sleep(100L * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Data
    static class TavilyResponse {
        private List<TavilyHit> results;
    }

    @Data
    static class TavilyHit {
        private String title;
        private String url;
        private String content;
        private Double score;

        @JsonProperty("published_date")
        private String publishedDate;
    }
}
