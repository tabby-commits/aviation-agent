package com.kama.jchatmind.agent.tools;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 已确认 SpaceNews 事件查询工具。
 *
 * <p>该工具只消费 review_status=confirmed 的结构化事件。候选事件为零确认时，
 * 返回硬闸门状态，不把 RAG 命中或 pending 候选暴露给模型做定量汇总。</p>
 */
@Component
public class NewsEventTool implements Tool {

    private final Path eventRoot;

    public NewsEventTool(@Value("${jchatmind.news-event-root:experiments/leo_paper_news_2020_2025/data}")
                         String eventRoot) {
        this.eventRoot = Path.of(eventRoot).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "NewsEventTool";
    }

    @Override
    public String getDescription() {
        return "只查询人工确认(review_status=confirmed)的SpaceNews结构化事件；"
                + "confirmed为0时强制返回AWAITING_HUMAN_REVIEW，不提供候选数值。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "NewsEventTool",
            description = "查询已人工确认的SpaceNews事件。eventKind仅可为engineering、actor、application；"
                    + "可按CN/US和年份过滤。仅返回review_status=confirmed并按duplicate_group_id去重的事件。"
                    + "若返回AWAITING_HUMAN_REVIEW/confirmed=0，必须停止新闻定量计算，不得改用RAG片段心算。"
    )
    public String queryConfirmedEvents(String eventKind,
                                       @Nullable String country,
                                       @Nullable Integer yearFrom,
                                       @Nullable Integer yearTo) {
        String normalizedKind = eventKind == null ? "" : eventKind.trim().toLowerCase(Locale.ROOT);
        String fileName = switch (normalizedKind) {
            case "engineering" -> "news_engineering_events.csv";
            case "actor" -> "news_actor_events.csv";
            case "application" -> "news_application_events.csv";
            default -> throw new IllegalArgumentException(
                    "eventKind must be engineering, actor, or application");
        };
        Path csvPath = eventRoot.resolve(fileName).normalize();
        if (!csvPath.startsWith(eventRoot)) {
            throw new IllegalArgumentException("invalid event path");
        }

        Map<String, CSVRecord> confirmed = readConfirmed(csvPath, country, yearFrom, yearTo);
        if (confirmed.isEmpty()) {
            return "AWAITING_HUMAN_REVIEW: confirmed=0；当前没有可用于正式统计的"
                    + normalizedKind + "新闻事件。不得引用pending候选、RAG片段或提及数形成发射/部署/在轨/主体能力结论。";
        }

        StringBuilder result = new StringBuilder("confirmed=")
                .append(confirmed.size())
                .append("（已按duplicate_group_id去重；存量与流量必须分别解释）：\n");
        for (CSVRecord record : confirmed.values()) {
            result.append("- event_id=").append(get(record, "event_id"))
                    .append(" | country=").append(get(record, "country_attribution"))
                    .append(" | operator=").append(get(record, "operator"))
                    .append(" | event_date=").append(get(record, "event_date"))
                    .append(" | report_date=").append(get(record, "report_date"))
                    .append(" | metric=").append(get(record, "metric_name"))
                    .append(" | value=").append(get(record, "value"))
                    .append(' ').append(get(record, "unit"))
                    .append(" | stock_or_flow=").append(get(record, "stock_or_flow"))
                    .append(" | source=").append(get(record, "source_url"))
                    .append(" | evidence=").append(get(record, "evidence_text"))
                    .append('\n');
        }
        return result.toString().stripTrailing();
    }

    private Map<String, CSVRecord> readConfirmed(Path csvPath,
                                                  String country,
                                                  Integer yearFrom,
                                                  Integer yearTo) {
        if (!Files.exists(csvPath)) {
            throw new IllegalStateException("news event file not found: " + csvPath);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .build();
        Map<String, CSVRecord> deduplicated = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord record : parser) {
                if (!"confirmed".equalsIgnoreCase(get(record, "review_status"))) {
                    continue;
                }
                if (country != null && !country.isBlank()
                        && !country.equalsIgnoreCase(get(record, "country_attribution"))) {
                    continue;
                }
                Integer year = eventYear(record);
                if (yearFrom != null && (year == null || year < yearFrom)) {
                    continue;
                }
                if (yearTo != null && (year == null || year > yearTo)) {
                    continue;
                }
                String duplicateGroup = get(record, "duplicate_group_id");
                String key = duplicateGroup.isBlank() ? get(record, "event_id") : duplicateGroup;
                deduplicated.putIfAbsent(key, record);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read news events: " + csvPath, e);
        }
        return deduplicated;
    }

    private Integer eventYear(CSVRecord record) {
        String date = get(record, "event_date");
        if (date.isBlank()) {
            date = get(record, "report_date");
        }
        if (date.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String get(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column).trim() : "";
    }
}
