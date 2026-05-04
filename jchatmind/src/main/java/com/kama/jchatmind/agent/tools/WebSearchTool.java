package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSearchTool implements SubAgentOnlyTool {

    private final SearchService searchService;
    private final AgenticSearchProperties properties;
    private final ObjectMapper objectMapper;

    public WebSearchTool(SearchService searchService,
                         AgenticSearchProperties properties,
                         ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "webSearch";
    }

    @Override
    public String getDescription() {
        return "子 Agent 专属外部搜索工具，底层通过 SearchService 调用 Tavily。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "webSearch",
            description = "执行外部 Web 搜索（底层 Tavily）。参数：query；count；timeRange（可选）——仅可使用 year/week/month/day 或缩写 y/w/m/d，或四位年份如 2025，或形如 2025-01~2025-06；不要使用 all/recent；不需要过滤时留空/null；domainFilter 域名白名单。返回结构化 JSON。"
    )
    public String webSearch(String query, Integer count, String timeRange, List<String> domainFilter) {
        int limit = clampCount(count);
        SearchResult result = searchService.search(new SearchRequest(query, limit, timeRange, domainFilter));
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 Web 搜索结果失败", e);
        }
    }

    private int clampCount(Integer count) {
        int requested = count == null || count <= 0
                ? properties.getSearch().getDefaultCount()
                : count;
        return Math.min(requested, properties.getSearch().getCountMax());
    }
}
