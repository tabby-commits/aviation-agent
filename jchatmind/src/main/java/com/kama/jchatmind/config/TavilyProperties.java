package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tavily Search API 接入配置。
 * PR1 阶段仅占位，enabled 默认 false，不影响现有功能。
 * 实际实现见 PR4（TavilySearchProvider）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.search.tavily")
public class TavilyProperties {

    /** 是否启用 Tavily 搜索；false 时 SearchService 自动降级到 KB-only */
    private boolean enabled = false;

    /** Tavily API Key，通过环境变量 TAVILY_API_KEY 注入 */
    private String apiKey = "";

    /** Tavily API 基础地址 */
    private String baseUrl = "https://api.tavily.com";

    /** 检索深度：basic（快速）或 advanced（精细，消耗更多额度） */
    private String searchDepth = "basic";

    /** 单次搜索默认返回条数 */
    private int defaultCount = 8;

    /** 单次 HTTP 请求超时（毫秒） */
    private int timeoutMs = 8000;

    /** 失败后最大重试次数（指数退避） */
    private int maxRetries = 2;
}
