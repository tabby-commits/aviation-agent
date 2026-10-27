package com.kama.jchatmind.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Formal runs only read hash-checked files; database and web searches cannot change the sample mid-run. */
@Component
public class FrozenSnapshot {
    private final Path root;
    private final String activeLeoId;
    private final ObjectMapper mapper;

    public FrozenSnapshot(@Value("${jchatmind.report.snapshot-root:experiments/report_pipeline_v1/snapshots}") String root,
                          @Value("${jchatmind.report.active-leo-snapshot-id:leo-2026-10-26-v1}") String activeLeoId,
                          ObjectMapper mapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.activeLeoId = activeLeoId;
        this.mapper = mapper;
    }

    public Snapshot loadLeo() {
        Path folder = root.resolve(activeLeoId).normalize();
        if (!folder.startsWith(root)) throw new IllegalStateException("Invalid snapshot path");
        try {
            JsonNode manifest = mapper.readTree(Files.readString(folder.resolve("manifest.json")));
            String id = manifest.path("snapshotId").asText();
            if (!activeLeoId.equals(id)) throw new IllegalStateException("Unexpected snapshot ID");
            Map<String, String> skillHashes = new java.util.LinkedHashMap<>();
            manifest.path("skills").fields().forEachRemaining(entry ->
                    skillHashes.put(entry.getKey(), entry.getValue().path("sha256").asText()));
            if (skillHashes.size() != 3 || skillHashes.values().stream().anyMatch(String::isBlank))
                throw new IllegalStateException("Snapshot is missing Skill versions/hashes");
            List<String> institutionalDomains = new ArrayList<>();
            manifest.path("institutionalDomains").forEach(node -> institutionalDomains.add(node.asText()));
            Path paperPath = checked(folder, "papers.csv", manifest);
            Path parameterPath = checked(folder, "parameters.csv", manifest);
            Path newsPath = checked(folder, "news_events.csv", manifest);
            List<Paper> papers = readPapers(paperPath);
            List<Parameter> parameters = readParameters(parameterPath);
            List<NewsEvent> newsEvents = readNews(newsPath);
            Set<String> paperIds = new HashSet<>();
            for (Paper paper : papers) {
                if (!paperIds.add(paper.docId()) || paper.year() < 2020 || paper.year() > 2025)
                    throw new IllegalStateException("Paper identity or year violates snapshot: " + paper.docId());
            }
            Set<String> parameterIds = new HashSet<>();
            for (Parameter parameter : parameters) {
                if (!parameterIds.add(parameter.id()) || !paperIds.contains(parameter.docId()))
                    throw new IllegalStateException("Parameter identity or paper missing: " + parameter.id());
            }
            if (papers.size() != manifest.path("files").path("papers.csv").path("rows").asInt()
                    || parameters.size() != manifest.path("files").path("parameters.csv").path("rows").asInt()
                    || newsEvents.size() != manifest.path("files").path("news_events.csv").path("rows").asInt())
                throw new IllegalStateException("Snapshot row count differs from manifest");
            return new Snapshot(id, Instant.parse(manifest.path("createdAt").asText()),
                    List.copyOf(papers), List.copyOf(parameters), List.copyOf(newsEvents),
                    manifest.path("files").path("papers.csv").path("sha256").asText(),
                    manifest.path("files").path("parameters.csv").path("sha256").asText(),
                    sha256(folder.resolve("manifest.json")), folder.toString(), Map.copyOf(skillHashes),
                    List.copyOf(institutionalDomains));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load frozen LEO snapshot", e);
        }
    }

    private Path checked(Path folder, String name, JsonNode manifest) throws IOException {
        Path file = folder.resolve(name).normalize();
        if (!file.startsWith(folder)) throw new IllegalStateException("Invalid snapshot file");
        String expected = manifest.path("files").path(name).path("sha256").asText();
        String actual = sha256(file);
        if (expected.isBlank() || !expected.equals(actual))
            throw new IllegalStateException("Snapshot hash mismatch: " + name);
        return file;
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private CSVParser parser(Path path) throws IOException {
        return CSVParser.parse(path, java.nio.charset.StandardCharsets.UTF_8,
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build());
    }

    private List<Paper> readPapers(Path path) throws IOException {
        List<Paper> result = new ArrayList<>();
        try (CSVParser rows = parser(path)) {
            for (CSVRecord row : rows) result.add(new Paper(row.get("doc_id"), row.get("source_db"),
                    row.get("title"), row.get("first_author_country"),
                    Integer.parseInt(row.get("publish_year")), row.get("doc_type"),
                    row.get("doi")));
        }
        return result;
    }

    private List<Parameter> readParameters(Path path) throws IOException {
        List<Parameter> result = new ArrayList<>();
        try (CSVParser rows = parser(path)) {
            for (CSVRecord row : rows) result.add(new Parameter(row.get("decision_id"), row.get("doc_id"),
                    row.get("parameter_family"), row.get("parameter_name_canonical"),
                    row.get("value_min"), row.get("value_max"), row.get("unit_normalized"),
                    row.get("condition_text"), row.get("evidence_text"), row.get("page_number"),
                    row.get("review_status"), row.get("first_author_country"), row.get("title")));
        }
        return result;
    }

    private List<NewsEvent> readNews(Path path) throws IOException {
        List<NewsEvent> result = new ArrayList<>();
        try (CSVParser rows = parser(path)) {
            for (CSVRecord row : rows) result.add(new NewsEvent(row.get("event_id"), row.get("event_date"),
                    row.get("report_date"), row.get("country"), row.get("actor"), row.get("action"),
                    row.get("object"), row.get("value"), row.get("unit"), row.get("as_of_date"),
                    row.get("original_url"), row.get("original_file"), row.get("original_sha256"),
                    row.get("original_fetched_at"),
                    row.get("original_excerpt"), row.get("duplicate_group_id"), row.get("source_kind")));
        }
        return result;
    }

    public record Snapshot(String id, Instant createdAt, List<Paper> papers, List<Parameter> parameters,
                           List<NewsEvent> newsEvents,
                           String paperHash, String parameterHash, String manifestHash, String folder,
                           Map<String, String> skillHashes, List<String> institutionalDomains) {}
    public record Paper(String docId, String database, String title, String country, int year,
                        String type, String doi) {}
    public record Parameter(String id, String docId, String family, String name, String min,
                            String max, String unit, String condition, String excerpt, String page,
                            String reviewStatus, String country, String title) {}
    public record NewsEvent(String id, String eventDate, String reportDate, String country, String actor,
                            String action, String object, String value, String unit, String asOfDate,
                            String originalUrl, String originalFile, String originalSha256,
                            String originalFetchedAt,
                            String originalExcerpt, String duplicateGroup, String sourceKind) {}
}
