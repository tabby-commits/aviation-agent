package com.kama.jchatmind.search;

import com.kama.jchatmind.config.TavilyProperties;
import com.kama.jchatmind.search.impl.TavilySearchProvider;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsTavilyFieldsToSearchHits() throws Exception {
        startServer(200, """
                {"results":[{"title":"Title","url":"https://example.com","content":"Snippet","published_date":"2025-01-02","score":0.9}]}
                """);
        TavilySearchProvider provider = new TavilySearchProvider(properties(true), WebClient.builder());

        SearchResult result = provider.search(new SearchRequest("query", 3, "2025", List.of("example.com")));

        assertThat(result.provider()).isEqualTo("tavily");
        assertThat(result.degraded()).isFalse();
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).title()).isEqualTo("Title");
        assertThat(result.hits().get(0).url()).isEqualTo("https://example.com");
        assertThat(result.hits().get(0).snippet()).isEqualTo("Snippet");
        assertThat(result.hits().get(0).publishedAt()).isEqualTo("2025-01-02");
    }

    @Test
    void unavailableWhenDisabledOrKeyMissing() {
        assertThat(new TavilySearchProvider(properties(false), WebClient.builder()).isAvailable()).isFalse();
    }

    @Test
    void skipsTimeRangeWhenAgentPassesAllSentinel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            requestBody.set(new String(req, StandardCharsets.UTF_8));
            String resp = "{}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        TavilySearchProvider provider = new TavilySearchProvider(properties(true), WebClient.builder());
        SearchResult result = provider.search(new SearchRequest("长征火箭 推力", 3, "all", List.of()));

        assertThat(result.degraded()).isFalse();
        assertThat(requestBody.get()).doesNotContain("\"time_range\"");
    }

    @Test
    void sendsTimeRangeYearWhenValidEnumPassed() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            requestBody.set(new String(req, StandardCharsets.UTF_8));
            String resp = "{}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        TavilyProperties p = properties(true);
        TavilySearchProvider provider = new TavilySearchProvider(p, WebClient.builder());
        provider.search(new SearchRequest("q", 2, "year", List.of()));

        assertThat(requestBody.get()).contains("\"time_range\":\"year\"");
    }

    private TavilyProperties properties(boolean enabled) {
        TavilyProperties properties = new TavilyProperties();
        properties.setEnabled(enabled);
        properties.setApiKey(enabled ? "test-key" : "");
        properties.setBaseUrl(server == null ? "http://localhost:0" : "http://localhost:" + server.getAddress().getPort());
        properties.setTimeoutMs(1000);
        properties.setMaxRetries(0);
        return properties;
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
