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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 冻结参数证据聚合工具：只读 S3 结果表，避免模型自由 SQL 混用全库与窗口口径。 */
@Component
public class ParameterSummaryTool implements Tool {

    private final Path resultRoot;

    public ParameterSummaryTool(
            @Value("${jchatmind.leo-result-root:experiments/leo_paper_news_2020_2025/results}")
            String resultRoot) {
        this.resultRoot = Path.of(resultRoot).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "ParameterSummaryTool";
    }

    @Override
    public String getDescription() {
        return "读取冻结S3参数结果，分别汇总论文数、参数论文数、参数记录数、年度和参数族，"
                + "并强制返回严格可比对数量及禁止排名边界。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "ParameterSummaryTool",
            description = "汇总冻结的LEO论文参数证据。输入yearFrom/yearTo和topFamilies；"
                    + "返回国别论文分母、含参数论文数、参数记录数、报告率、年度分布、参数族Top及严格可比对数量。"
                    + "必须原样遵守返回的claim boundary，不得把报告率或记录密度解释为成熟度、透明度、质量或性能优势。"
    )
    public String summarizeFrozenEvidence(@Nullable Integer yearFrom,
                                          @Nullable Integer yearTo,
                                          @Nullable Integer topFamilies) {
        int from = yearFrom == null ? 2020 : yearFrom;
        int to = yearTo == null ? 2025 : yearTo;
        if (from > to) {
            throw new IllegalArgumentException("yearFrom must be <= yearTo");
        }
        int topN = topFamilies == null ? 10 : Math.max(1, Math.min(topFamilies, 20));

        List<Map<String, String>> details = read("parameter_evidence_details.csv");
        List<Map<String, String>> paperYears = read("paper_bibliometrics_by_country_year.csv");
        List<Map<String, String>> comparability = read("parameter_comparability_summary.csv");

        String snapshotId = firstNonBlank(details, "snapshot_id");
        Map<String, Integer> denominators = new LinkedHashMap<>();
        Map<String, Map<Integer, Integer>> denominatorByYear = new LinkedHashMap<>();
        for (String country : List.of("CN", "US")) {
            denominators.put(country, 0);
            denominatorByYear.put(country, new LinkedHashMap<>());
        }
        for (Map<String, String> row : paperYears) {
            String country = row.get("country");
            int year = integer(row.get("year"));
            if (!denominators.containsKey(country) || year < from || year > to) {
                continue;
            }
            int count = integer(row.get("first_author_count"));
            denominators.merge(country, count, Integer::sum);
            denominatorByYear.get(country).merge(year, count, Integer::sum);
        }

        Map<String, Set<String>> parameterPapers = new LinkedHashMap<>();
        Map<String, Set<String>> parameterRecords = new LinkedHashMap<>();
        Map<String, Map<Integer, Set<String>>> papersByYear = new LinkedHashMap<>();
        Map<String, Map<Integer, Set<String>>> recordsByYear = new LinkedHashMap<>();
        Map<String, Map<String, FamilyCount>> families = new LinkedHashMap<>();
        for (String country : List.of("CN", "US")) {
            parameterPapers.put(country, new LinkedHashSet<>());
            parameterRecords.put(country, new LinkedHashSet<>());
            papersByYear.put(country, new LinkedHashMap<>());
            recordsByYear.put(country, new LinkedHashMap<>());
            families.put(country, new HashMap<>());
        }
        for (Map<String, String> row : details) {
            String country = row.get("first_author_country");
            int year = integer(row.get("publish_year"));
            if (!parameterPapers.containsKey(country) || year < from || year > to) {
                continue;
            }
            String docId = row.get("doc_id");
            String decisionId = row.get("decision_id");
            String family = row.getOrDefault("parameter_family", "").isBlank()
                    ? "(missing family)" : row.get("parameter_family");
            parameterPapers.get(country).add(docId);
            parameterRecords.get(country).add(decisionId);
            papersByYear.get(country).computeIfAbsent(year, ignored -> new LinkedHashSet<>()).add(docId);
            recordsByYear.get(country).computeIfAbsent(year, ignored -> new LinkedHashSet<>()).add(decisionId);
            FamilyCount count = families.get(country).computeIfAbsent(family, ignored -> new FamilyCount());
            count.papers.add(docId);
            count.records.add(decisionId);
        }

        StringBuilder out = new StringBuilder()
                .append("snapshot_id=").append(snapshotId)
                .append("; window=").append(from).append('-').append(to).append('\n')
                .append("statistical_unit: paper denominator=unique included WoS paper by first-author country; ")
                .append("parameter_papers=unique doc_id; parameter_records=unique decision_id.\n");
        for (String country : List.of("CN", "US")) {
            int papers = denominators.get(country);
            int reportingPapers = parameterPapers.get(country).size();
            double rate = papers == 0 ? 0 : reportingPapers * 100.0 / papers;
            out.append(country).append(": parameter_papers=").append(reportingPapers)
                    .append(" / papers=").append(papers)
                    .append("; report_rate=").append(String.format(java.util.Locale.ROOT, "%.1f%%", rate))
                    .append("; parameter_records=").append(parameterRecords.get(country).size()).append('\n');
            out.append(country).append(" annual paper/reporting/records:");
            for (int year = from; year <= to; year++) {
                out.append(' ').append(year).append('=')
                        .append(denominatorByYear.get(country).getOrDefault(year, 0)).append('/')
                        .append(papersByYear.get(country).getOrDefault(year, Set.of()).size()).append('/')
                        .append(recordsByYear.get(country).getOrDefault(year, Set.of()).size());
            }
            out.append('\n').append(country).append(" top parameter families (paper_count/record_count): ");
            out.append(families.get(country).entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, FamilyCount>>comparingInt(
                            entry -> entry.getValue().records.size()).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(topN)
                    .map(entry -> entry.getKey() + "=" + entry.getValue().papers.size()
                            + "/" + entry.getValue().records.size())
                    .collect(java.util.stream.Collectors.joining(", ")))
                    .append('\n');
        }

        Map<String, String> comparison = comparability.isEmpty() ? Map.of() : comparability.get(0);
        out.append("strict_comparable_pair_count=")
                .append(comparison.getOrDefault("strict_comparable_pair_count", "UNKNOWN"))
                .append("; ").append(comparison.getOrDefault("result_statement", ""))
                .append("; claim_boundary=")
                .append(comparison.getOrDefault("claim_boundary", "不得形成中美技术性能排名。"))
                .append('\n')
                .append("MANDATORY INTERPRETATION BOUNDARY: 参数报告率只表示冻结论文中至少存在一条结构化参数证据的比例；")
                .append("不得解释为技术成熟度、透明度、证据质量或性能优势。记录数和参数族覆盖同样不得作为国别优劣排名。");
        return out.toString();
    }

    private List<Map<String, String>> read(String fileName) {
        Path path = resultRoot.resolve(fileName).normalize();
        if (!path.startsWith(resultRoot) || !Files.exists(path)) {
            throw new IllegalStateException("frozen result file not found: " + path);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).build();
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : parser.getHeaderMap().keySet()) {
                    row.put(header, record.isSet(header) ? record.get(header).trim() : "");
                }
                rows.add(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read frozen result: " + path, e);
        }
        return rows;
    }

    private String firstNonBlank(List<Map<String, String>> rows, String key) {
        return rows.stream().map(row -> row.getOrDefault(key, ""))
                .filter(value -> !value.isBlank()).findFirst().orElse("UNKNOWN");
    }

    private int integer(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class FamilyCount {
        private final Set<String> papers = new LinkedHashSet<>();
        private final Set<String> records = new LinkedHashSet<>();
    }
}
