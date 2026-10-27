package com.kama.jchatmind.report;

import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.search.model.SearchHit;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import com.kama.jchatmind.service.RagService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A fresh search and raw-page capture for each task; search snippets never become evidence. */
@Component
public class WebEvidenceCollector {
    private static final Pattern CONTENT = Pattern.compile("(?is)<(article|main)\\b[^>]*>(.*?)</\\1>");
    private static final Pattern URL_PATTERN =
            Pattern.compile("https://[\\w.-]+(?:/[\\w./%#?=&:+~-]*)?");
    private final SearchService search;
    private final RagService rag;
    private final Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public WebEvidenceCollector(SearchService search, RagService rag,
            @Value("${jchatmind.report.run-source-root:data/report_runs}") String root) {
        this.search = search;
        this.rag = rag;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public record Evidence(String id, String title, String url, String publishedAt, String fetchedAt,
                           String sha256, String rawPath, String text) {}
    public record Retrieval(List<Evidence> evidence, List<SearchResult> searches, List<String> failures,
                            List<String> kbClueUrls) {}

    /** Legacy budget kept for existing callers: 2 queries, 6 pages, 120 seconds. */
    public Retrieval collect(String runId, List<String> queries) {
        return collect(runId, queries, 2, 6, Duration.ofSeconds(120), null);
    }

    public Retrieval collect(String runId, List<String> queries, int maxQueries, int maxPages,
                             Duration budget, String newsKbId) {
        List<Evidence> evidence = new ArrayList<>();
        List<SearchResult> searches = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long deadline = System.nanoTime() + budget.toNanos();
        for (String query : queries.stream().limit(maxQueries).toList()) {
            if (query == null || query.isBlank() || System.nanoTime() >= deadline) continue;
            int before = evidence.size();
            searchAndCapture(runId, query, searches, failures, seen, evidence, 3, maxPages, deadline);
            if (evidence.size() == before && query.contains("site:") && System.nanoTime() < deadline) {
                String fallback = query.replaceAll("(?i)site:[^\\s]+", "").trim();
                searchAndCapture(runId, fallback, searches, failures, seen, evidence, 3, maxPages, deadline);
            } else if (evidence.size() == before && System.nanoTime() < deadline) {
                String official = officialQuery(query);
                if (official != null)
                    searchAndCapture(runId, official, searches, failures, seen, evidence, 3, maxPages, deadline);
            }
        }
        if (System.nanoTime() >= deadline) failures.add("本任务网页抓取达到 " + budget.toSeconds() + " 秒上限");
        return new Retrieval(List.copyOf(evidence), List.copyOf(searches), List.copyOf(failures), List.of());
    }

    /** L1 knowledge-base clue pass: KB hits suggest URLs, which still go through raw-page capture. */
    public List<Evidence> collectFromKnowledgeBase(String runId, String kbId, String question,
                                                   Set<String> seen, List<String> failures) {
        List<Evidence> evidence = new ArrayList<>();
        if (kbId == null || kbId.isBlank()) return evidence;
        for (String url : clueUrls(kbId, question)) {
            if (seen.contains(url)) continue;
            seen.add(url);
            try {
                Evidence captured = capture(runId, new SearchHit(url, url, null, null, null, "kb-clue"));
                if (captured != null) evidence.add(captured);
            } catch (Exception e) {
                failures.add(url + "：" + e.getClass().getSimpleName());
            }
        }
        return evidence;
    }

    private List<String> clueUrls(String kbId, String question) {
        List<String> urls = new ArrayList<>();
        try {
            for (String chunk : rag.hybridSearch(kbId, question, 5)) {
                Matcher matcher = URL_PATTERN.matcher(chunk);
                while (matcher.find() && urls.size() < 3)
                    if (!urls.contains(matcher.group())) urls.add(matcher.group());
            }
        } catch (Exception ignored) { /* KB is a clue source only; capture failures are non-fatal. */ }
        return urls;
    }

    private void searchAndCapture(String runId, String query, List<SearchResult> searches,
                                  List<String> failures, Set<String> seen, List<Evidence> evidence,
                                  int perQueryLimit, int maxPages, long deadline) {
        if (evidence.size() >= maxPages) return;
        SearchResult result = search.search(new SearchRequest(query, 8, null, List.of()));
        searches.add(result);
        if (result.degraded()) failures.add("检索失败：" + result.errorMessage());
        int accepted = 0, attempted = 0;
        for (SearchHit hit : result.hits()) {
            if (accepted >= perQueryLimit || evidence.size() >= maxPages || attempted >= 5
                    || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) break;
            if (hit.url() == null || !seen.add(hit.url())) continue;
            attempted++;
            try {
                Evidence captured = capture(runId, hit);
                if (captured != null) {
                    evidence.add(captured);
                    accepted++;
                }
            } catch (Exception e) {
                failures.add(hit.url() + "：" + e.getClass().getSimpleName());
            }
        }
    }

    private String officialQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("spacex") || lower.contains("falcon") || lower.contains("starship")
                || lower.contains("starlink"))
            return query + " site:spacex.com";
        if (lower.contains("blue origin") || lower.contains("new glenn"))
            return query + " site:blueorigin.com";
        if (lower.contains("amazon") || lower.contains("kuiper"))
            return query + " site:aboutamazon.com";
        // Chinese actors have no stable official-domain guess; fall back to an authoritative tracker.
        if (lower.contains("千帆") || lower.contains("垣信") || lower.contains("国网")
                || lower.contains("星网") || lower.contains("guowang") || lower.contains("qianfan")
                || lower.contains("spacesail") || lower.contains("galaxy space") || lower.contains("银河航天"))
            return query + " eoportal";
        if (lower.contains("starlink") || lower.contains("kuiper") || lower.contains("constellation"))
            return query + " eoportal";
        return null;
    }

    private Evidence capture(String runId, SearchHit hit) throws Exception {
        URI uri = URI.create(hit.url());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null) return null;
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()) return null;
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "JChatMindResearch/1.0")
                .header("Cache-Control", "no-cache").header("Pragma", "no-cache").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.cancel(true);
            throw e;
        }
        if (response.statusCode() != 200 || response.body().length > 15_000_000) return null;
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        boolean pdf = contentType.contains("application/pdf") || uri.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf");
        if (!pdf && !contentType.contains("html") && !contentType.contains("text/plain")) return null;
        String text = pdf ? extractPdf(response.body()) : extract(new String(response.body(), charset(contentType)));
        if (text.length() < 180 || text.length() < 1800 &&
                (text.toLowerCase(Locale.ROOT).contains("page not found")
                        || text.toLowerCase(Locale.ROOT).contains("404 not found"))) return null;
        Path folder = root.resolve(runId).normalize();
        if (!folder.startsWith(root)) throw new IllegalArgumentException("Invalid run ID");
        Files.createDirectories(folder);
        String id = runId + ":source:" + UUID.randomUUID();
        Path raw = folder.resolve(id.substring(id.lastIndexOf(':') + 1) + (pdf ? ".pdf" : ".html"));
        Files.write(raw, response.body());
        return new Evidence(id, hit.title() == null ? uri.getHost() : hit.title(), uri.toString(),
                hit.publishedAt(), Instant.now().toString(), FrozenSnapshot.sha256(raw), raw.toString(), text);
    }

    private Charset charset(String contentType) {
        Matcher match = Pattern.compile("charset=([a-zA-Z0-9_-]+)").matcher(contentType);
        if (match.find()) {
            try { return Charset.forName(match.group(1)); }
            catch (Exception ignored) { /* UTF-8 is the safe fallback for web pages. */ }
        }
        return StandardCharsets.UTF_8;
    }

    private String extract(String html) {
        String cleaned = html.replaceAll("(?is)<(script|style|svg|nav|footer|noscript)\\b[^>]*>.*?</\\1>", " ");
        Matcher match = CONTENT.matcher(cleaned);
        if (match.find()) cleaned = match.group(2);
        cleaned = cleaned.replaceAll("(?is)<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(cleaned).replaceAll("\\s+", " ").trim();
    }

    String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ").trim();
        }
    }
}
