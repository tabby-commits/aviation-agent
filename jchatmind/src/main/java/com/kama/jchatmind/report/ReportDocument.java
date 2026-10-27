package com.kama.jchatmind.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The persisted handoff contract shared by the report agents, API and exporters. */
public record ReportDocument(
        String id, String runId, String sessionId, String topic, String title, String question, String snapshotId,
        int version, Instant createdAt, List<Source> sources, List<Calculation> calculations,
        List<Claim> abstractLines, List<Dimension> dimensions, List<Claim> summaryLines,
        Map<String, String> excludedIndicators, Map<String, String> skillHashes,
        List<StatusCard> statusCards, List<GapRow> gapMatrix, List<Claim> overallAssessment) {

    public record Source(String id, String kind, String title, String url, String location,
                         String excerpt, String sha256, String retrievedAt, String verificationStatus) {}

    public record Calculation(String id, String method, String formula, List<String> inputIds,
                              String value, String unit) {}

    /** A claim is one independently cited assertion. Numeric claims point to a calculation. */
    public record Claim(String id, String text, List<String> sourceIds, String calculationId,
                        String value, String unit) {}

    public record Dimension(String name, List<Indicator> indicators) {}

    public record Indicator(String code, String name, List<Claim> paragraphs, Table table) {}

    public record Table(String title, List<String> headers, List<Row> rows) {}

    public record Row(List<String> cells, List<String> sourceIds, List<String> calculationIds) {}

    /** Per-actor status card assembled from field-level verified evidence. */
    public record StatusCard(String actorId, String actorName, String country, List<CardField> fields) {}

    /** One field of a status card; stock fields (inOrbit) must carry asOfDate. */
    public record CardField(String field, String value, String asOfDate, String tier,
                            String sourceId, String quote) {}

    /** One row of the comparison gap matrix; basisClaimIds point to verified claims. */
    public record GapRow(String aspect, String usStatus, String cnStatus, String gapStatement,
                         String evidenceStrength, List<String> basisClaimIds) {}
}
