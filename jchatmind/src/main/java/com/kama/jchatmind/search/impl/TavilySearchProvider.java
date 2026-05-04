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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TavilySearchProvider implements SearchProvider {

    /**
     * Tavily Search API {@code time_range} 允许的枚举（见官方文档）。
     * 传入 {@code all}/{@code recent} 等会直接 4xx，故需在发送前剔除或放行。
     */
    private static final Set<String> TAVILY_TIME_RANGE_VALUES = Set.of(
            "day", "week", "month", "year", "d", "w", "m", "y");

    /** 调用方常用的“不限时间范围”占位词，不向 Tavily 发送 {@code time_range}。 */
    private static final Set<String> OPEN_ENDED_TIME_RANGE_SENTINELS = Set.of(
            "all", "any", "none", "unlimited", "ever", "everything", "lifetime", "na", "n/a");

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
            applyTimeRange(body, request.timeRange().trim());
        }
        if (request.domainFilter() != null && !request.domainFilter().isEmpty()) {
            body.put("include_domains", request.domainFilter());
        }
        return body;
    }

    private void applyTimeRange(Map<String, Object> body, String timeRange) {
        if (!StringUtils.hasText(timeRange)) {
            return;
        }
        String lc = timeRange.toLowerCase(Locale.ROOT);
        if (OPEN_ENDED_TIME_RANGE_SENTINELS.contains(lc)) {
            return;
        }
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
        if (TAVILY_TIME_RANGE_VALUES.contains(lc)) {
            body.put("time_range", lc);
            return;
        }
        /* 未知 token（如 recent、last month）会令 Tavily 拒绝请求；跳过时间筛选，保证检索可用 */
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
