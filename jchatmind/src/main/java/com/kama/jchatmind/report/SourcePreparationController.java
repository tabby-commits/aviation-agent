package com.kama.jchatmind.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.search.model.SearchRequest;
import com.kama.jchatmind.search.model.SearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Discovery and capture happen outside formal report runs and never mutate a frozen snapshot. */
@RestController
@RequestMapping("/api/report-sources")
public class SourcePreparationController {
    private final SearchService search;
    private final Path stagingRoot;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public SourcePreparationController(SearchService search, ObjectMapper mapper,
            @Value("${jchatmind.report.source-staging-root:data/report_source_staging}") String stagingRoot) {
        this.search = search;
        this.mapper = mapper;
        this.stagingRoot = Path.of(stagingRoot).toAbsolutePath().normalize();
    }

    public record DiscoverRequest(String query, List<String> domains) {}
    public record CaptureRequest(String url) {}
    public record CapturedSource(String id, String url, String fetchedAt, String sha256,
                                 String rawPath, int statusCode) {}

    @PostMapping("/discover")
    public SearchResult discover(@RequestBody DiscoverRequest request) {
        if (request.query() == null || request.query().isBlank())
            throw new IllegalArgumentException("Search query is required");
        return search.search(new SearchRequest(request.query(), 10, null, request.domains()));
    }

    @PostMapping("/capture")
    public CapturedSource capture(@RequestBody CaptureRequest request) {
        try {
            URI uri = URI.create(request.url());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Only public HTTPS sources can be captured");
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress())
                throw new IllegalArgumentException("Private addresses are not source pages");
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 5_000_000)
                throw new IllegalStateException("Page unavailable or too large: " + response.statusCode());
            String id = UUID.randomUUID().toString();
            Path folder = stagingRoot.resolve(id);
            Files.createDirectories(folder);
            Path raw = folder.resolve("original.html");
            Files.write(raw, response.body());
            CapturedSource captured = new CapturedSource(id, uri.toString(), Instant.now().toString(),
                    FrozenSnapshot.sha256(raw), raw.toString(), response.statusCode());
            mapper.writeValue(folder.resolve("source.json").toFile(), captured);
            return captured;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot capture original source: " + e.getMessage(), e);
        }
    }
}
