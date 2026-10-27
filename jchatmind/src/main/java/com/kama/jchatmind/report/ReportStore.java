package com.kama.jchatmind.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ReportStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    public void initialize() {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/report-schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize report schema", e);
        }
    }

    public String start(String sessionId, String topic, String snapshotId, Map<String, String> skillHashes) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO analysis_run(id,session_id,topic,snapshot_id,status,started_at,skill_hashes) "
                        + "VALUES(?::uuid,?,?,?,'running',?,?::jsonb)",
                id, sessionId, topic, snapshotId, java.sql.Timestamp.from(Instant.now()), json(skillHashes));
        return id;
    }

    public void stage(String runId, String stage, String status, Object input, Object output) {
        Map<String, Object> entry = Map.of("stage", stage, "status", status,
                "at", Instant.now().toString(), "input", input, "output", output);
        jdbc.update("UPDATE analysis_run SET stages=stages || ?::jsonb WHERE id=?::uuid",
                json(List.of(entry)), runId);
    }

    public void finish(String runId, String status, String error) {
        jdbc.update("UPDATE analysis_run SET status=?,finished_at=?,error_text=? WHERE id=?::uuid",
                status, java.sql.Timestamp.from(Instant.now()), error, runId);
    }

    public int nextVersion(String sessionId, String topic) {
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM report_version "
                + "WHERE session_id=? AND topic=?", Integer.class, sessionId, topic);
        return version == null ? 1 : version;
    }

    public void save(ReportDocument report) {
        jdbc.update("INSERT INTO report_version(id,run_id,session_id,topic,version,created_at,body) "
                        + "VALUES(?::uuid,?::uuid,?,?,?,?,?::jsonb)", report.id(), report.runId(),
                report.sessionId(), report.topic(), report.version(),
                java.sql.Timestamp.from(report.createdAt()), json(report));
        for (ReportDocument.Source source : report.sources()) {
            jdbc.update("INSERT INTO evidence_source(id,snapshot_id,body) VALUES(?,?,?::jsonb) "
                            + "ON CONFLICT(id) DO UPDATE SET body=EXCLUDED.body",
                    source.id(), report.snapshotId(), json(source));
        }
    }

    public Optional<ReportDocument> get(String reportId) {
        List<String> bodies = jdbc.queryForList("SELECT body::text FROM report_version WHERE id=?::uuid",
                String.class, reportId);
        return bodies.stream().findFirst().map(body -> parse(body, ReportDocument.class));
    }

    public Optional<ReportDocument> latest(String sessionId, String topic) {
        List<String> bodies = jdbc.queryForList("SELECT body::text FROM report_version "
                + "WHERE session_id=? AND topic=? ORDER BY version DESC LIMIT 1", String.class, sessionId, topic);
        return bodies.stream().findFirst().map(body -> parse(body, ReportDocument.class));
    }

    public Optional<ReportDocument> latestInSession(String sessionId) {
        List<String> bodies = jdbc.queryForList("SELECT body::text FROM report_version "
                + "WHERE session_id=? ORDER BY created_at DESC LIMIT 1", String.class, sessionId);
        return bodies.stream().findFirst().map(body -> parse(body, ReportDocument.class));
    }

    public record ReportSummary(String id, int version, String createdAt, String runId) {}

    public List<ReportSummary> versions(String sessionId) {
        return jdbc.query("SELECT id::text,version,created_at::text,run_id::text FROM report_version "
                        + "WHERE session_id=? ORDER BY created_at DESC",
                (rs, rowNum) -> new ReportSummary(rs.getString(1), rs.getInt(2),
                        rs.getString(3), rs.getString(4)), sessionId);
    }

    public Optional<ReportDocument.Source> source(String id) {
        List<String> bodies = jdbc.queryForList("SELECT body::text FROM evidence_source WHERE id=?",
                String.class, id);
        return bodies.stream().findFirst().map(body -> parse(body, ReportDocument.Source.class));
    }

    public record RunArchive(String id, String sessionId, String topic, String snapshotId,
                             String status, String startedAt, String finishedAt,
                             JsonNode stages, JsonNode skillHashes, String errorText) {}

    public Optional<RunArchive> run(String id) {
        List<RunArchive> rows = jdbc.query("SELECT id::text,session_id,topic,snapshot_id,status,"
                        + "started_at::text,finished_at::text,stages::text,skill_hashes::text,error_text "
                        + "FROM analysis_run WHERE id=?::uuid", (rs, rowNum) -> new RunArchive(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        parse(rs.getString(8), JsonNode.class), parse(rs.getString(9), JsonNode.class),
                        rs.getString(10)), id);
        return rows.stream().findFirst();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private <T> T parse(String body, Class<T> type) {
        try { return mapper.readValue(body, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
