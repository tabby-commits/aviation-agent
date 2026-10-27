package com.kama.jchatmind.report;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.service.SkillService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.kama.jchatmind.report.ReportDocument.*;

/** Explicit role handoffs. Only the cited, deterministic outputs of each role may reach the report. */
@Service
public class ReportPipeline {
    private final FrozenSnapshot snapshots;
    private final SkillService skills;
    private final ReportStore store;
    private final CitationValidator validator;
    private final NewsEventVerifier newsVerifier;

    public ReportPipeline(FrozenSnapshot snapshots, SkillService skills, ReportStore store,
                          CitationValidator validator, NewsEventVerifier newsVerifier) {
        this.snapshots = snapshots;
        this.skills = skills;
        this.store = store;
        this.validator = validator;
        this.newsVerifier = newsVerifier;
    }

    public ReportDocument run(String sessionId, Consumer<String> progress) {
        FrozenSnapshot.Snapshot snapshot = snapshots.loadLeo();
        Map<String, String> hashes = skillLedger();
        if (!hashes.equals(snapshot.skillHashes()))
            throw new IllegalStateException("Skill versions differ from the frozen snapshot; create a new snapshot");
        String runId = store.start(sessionId, "leo", snapshot.id(), hashes);
        try {
            invokeForRole(runId, "coordinator", "competitiveness-framework", snapshot.id());
            progress.accept("协调：确定案例、时间窗和适用指标");
            Plan plan = new Plan("leo", 2020, 2025, List.of("9", "11", "13"), snapshot.id());
            store.stage(runId, "coordinator", "done", Map.of("sessionId", sessionId), plan);

            progress.accept("论文证据：计算冻结样本中的论文与参数记录");
            invokeForRole(runId, "paper-evidence-agent", "data-resource-rules", snapshot.id());
            PaperHandoff paper = paperAgent(snapshot, plan);
            store.stage(runId, "paper-evidence-agent", "done", plan,
                    Map.of("papers", paper.papers().size(), "parameters", paper.parameters().size(),
                            "paperHash", snapshot.paperHash(), "parameterHash", snapshot.parameterHash()));

            progress.accept("新闻证据：检查原始机构材料与去重条件");
            invokeForRole(runId, "news-evidence-agent", "data-resource-rules", snapshot.id());
            NewsHandoff news = newsAgent(snapshot);
            store.stage(runId, "news-evidence-agent", "done", Map.of("snapshotId", snapshot.id()), news);

            progress.accept("事实核验：检查记录、日期、数值与来源定位");
            VerifiedHandoff verified = factVerificationAgent(snapshot, paper, news);
            store.stage(runId, "fact-verification-agent", "done",
                    Map.of("paperCount", paper.papers().size(), "parameterCount", paper.parameters().size()),
                    Map.of("machineVerifiedPapers", verified.papers().size(),
                            "machineVerifiedParameterRecords", verified.parameters().size(),
                            "verifiedNewsEvents", verified.newsEvents().size(),
                            "excludedNews", news.events().size() - verified.newsEvents().size(),
                            "excludedNewsReasons", verified.rejectedNews(),
                            "excludedParameters", paper.parameters().size() - verified.parameters().size()));

            progress.accept("确定性统计：保存公式和输入记录");
            CalculationHandoff calculated = calculationTool(verified);
            store.stage(runId, "deterministic-calculation", "done",
                    Map.of("inputPaperIds", verified.papers().stream().map(FrozenSnapshot.Paper::docId).toList(),
                            "inputParameterIds", verified.parameters().stream().map(FrozenSnapshot.Parameter::id).toList()),
                    Map.of("calculationIds", calculated.calculations().stream().map(Calculation::id).toList()));

            progress.accept("统一分析：组织有证据的维度与指标");
            invokeForRole(runId, "unified-analysis-agent", "ci-analysis-methods", snapshot.id());
            ReportDocument report = analysisAgent(sessionId, runId, snapshot, hashes, plan, calculated);
            store.stage(runId, "unified-analysis-agent", "done",
                    Map.of("calculationIds", calculated.calculations().stream().map(Calculation::id).toList()),
                    Map.of("claimIds", claimIds(report), "excludedIndicators", report.excludedIndicators()));

            progress.accept("引用检查：逐项复核来源与计算值");
            validator.validate(report);
            store.stage(runId, "citation-check", "done", Map.of("claimIds", claimIds(report)),
                    Map.of("sourceCount", report.sources().size(), "status", "passed"));
            store.save(report);
            store.finish(runId, "completed", null);
            progress.accept("报告已生成");
            return report;
        } catch (Exception e) {
            store.finish(runId, "failed", e.getMessage());
            throw e;
        }
    }

    private Map<String, String> skillLedger() {
        Map<String, String> ledger = new LinkedHashMap<>();
        for (String name : List.of("competitiveness-framework", "data-resource-rules", "ci-analysis-methods")) {
            SkillDefinition skill = skills.getSkillByName(name)
                    .orElseThrow(() -> new IllegalStateException("Required Skill not loaded: " + name));
            // Invoke the actual Skill service, not just a filename lookup.
            String invoked = skills.invokeSkill(name, "低轨卫星星座网络；2020—2025；本次冻结样本");
            if (!invoked.contains(skill.getInstructions()))
                throw new IllegalStateException("Skill invocation did not return instructions: " + name);
            ledger.put(name, sha256(skill.getRawContent()));
        }
        return Map.copyOf(ledger);
    }

    private void invokeForRole(String runId, String role, String skillName, String snapshotId) {
        String context = "角色=" + role + "；案例=低轨卫星星座网络；snapshot_id=" + snapshotId;
        String result = skills.invokeSkill(skillName, context);
        if (result.startsWith("Error:")) throw new IllegalStateException(result);
        store.stage(runId, "skill:" + skillName + ":" + role, "done",
                Map.of("role", role, "context", context),
                Map.of("result", result, "sha256", sha256(result)));
    }

    private String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private PaperHandoff paperAgent(FrozenSnapshot.Snapshot snapshot, Plan plan) {
        if (!plan.snapshotId().equals(snapshot.id())) throw new IllegalStateException("Snapshot changed");
        List<FrozenSnapshot.Paper> papers = snapshot.papers().stream()
                .filter(p -> "CN".equals(p.country()) || "US".equals(p.country())).toList();
        List<FrozenSnapshot.Parameter> parameters = snapshot.parameters().stream()
                .filter(p -> "CN".equals(p.country()) || "US".equals(p.country())).toList();
        return new PaperHandoff(papers, parameters);
    }

    private NewsHandoff newsAgent(FrozenSnapshot.Snapshot snapshot) {
        return new NewsHandoff(snapshot.newsEvents(), snapshot.newsEvents().isEmpty()
                ? "未提供带原始机构网页、获取时间和哈希的新闻事件快照" : "待机器核验原文及去重");
    }

    private VerifiedHandoff factVerificationAgent(FrozenSnapshot.Snapshot snapshot, PaperHandoff paper,
                                                   NewsHandoff news) {
        Set<String> paperIds = new HashSet<>();
        List<FrozenSnapshot.Paper> validPapers = paper.papers().stream()
                .filter(p -> !p.docId().isBlank() && !p.title().isBlank() && p.year() >= 2020 && p.year() <= 2025)
                .filter(p -> paperIds.add(p.docId())).toList();
        Set<String> validDocIds = validPapers.stream().map(FrozenSnapshot.Paper::docId).collect(Collectors.toSet());
        Map<String, String> countryByDocId = validPapers.stream()
                .collect(Collectors.toMap(FrozenSnapshot.Paper::docId, FrozenSnapshot.Paper::country));
        Set<String> parameterIds = new HashSet<>();
        List<FrozenSnapshot.Parameter> validParameters = paper.parameters().stream()
                .filter(p -> parameterIds.add(p.id()) && validDocIds.contains(p.docId())
                        && p.country().equals(countryByDocId.get(p.docId())))
                .filter(p -> "REVIEWED".equalsIgnoreCase(p.reviewStatus()))
                .filter(p -> !p.excerpt().isBlank() && !p.page().isBlank() && !p.name().isBlank())
                .toList();
        // Verification status is deliberately machine verification of structured records.
        // An extracted quotation is not represented as a check against a source PDF.
        Set<String> eventIds = new HashSet<>();
        Set<String> duplicateGroups = new HashSet<>();
        List<FrozenSnapshot.NewsEvent> validNews = new ArrayList<>();
        Map<String, String> rejectedNews = new LinkedHashMap<>();
        for (FrozenSnapshot.NewsEvent event : news.events()) {
            String failure = newsVerifier.failure(snapshot, event, eventIds, duplicateGroups);
            if (failure == null) {
                eventIds.add(event.id());
                duplicateGroups.add(event.duplicateGroup());
                validNews.add(event);
            } else {
                rejectedNews.put(event.id(), failure);
            }
        }
        return new VerifiedHandoff(validPapers, validParameters, validNews, rejectedNews);
    }

    private CalculationHandoff calculationTool(VerifiedHandoff verified) {
        List<Calculation> calculations = new ArrayList<>();
        List<String> allIds = verified.papers().stream().map(FrozenSnapshot.Paper::docId).sorted().toList();
        calculations.add(new Calculation("paper-total", "count distinct doc_id",
                "COUNT(DISTINCT doc_id), included, 2020<=year<=2025, CN/US first author",
                allIds, String.valueOf(allIds.size()), "篇"));
        for (String country : List.of("CN", "US")) {
            List<String> ids = verified.papers().stream().filter(p -> country.equals(p.country()))
                    .map(FrozenSnapshot.Paper::docId).sorted().toList();
            calculations.add(new Calculation("paper-" + country, "count distinct doc_id",
                    "COUNT(DISTINCT doc_id) WHERE first_author_country=" + country,
                    ids, String.valueOf(ids.size()), "篇"));
            for (int year = 2020; year <= 2025; year++) {
                final int y = year;
                List<String> yearly = verified.papers().stream()
                        .filter(p -> country.equals(p.country()) && p.year() == y)
                        .map(FrozenSnapshot.Paper::docId).sorted().toList();
                calculations.add(new Calculation("paper-" + country + "-" + year, "count distinct doc_id",
                        "COUNT(DISTINCT doc_id) WHERE country=" + country + " AND year=" + year,
                        yearly, String.valueOf(yearly.size()), "篇"));
            }
            int start = verified.papers().stream().filter(p -> country.equals(p.country()) && p.year() == 2020)
                    .map(FrozenSnapshot.Paper::docId).collect(Collectors.toSet()).size();
            int end = verified.papers().stream().filter(p -> country.equals(p.country()) && p.year() == 2025)
                    .map(FrozenSnapshot.Paper::docId).collect(Collectors.toSet()).size();
            calculations.add(new Calculation("paper-" + country + "-delta", "difference",
                    "count(" + country + ",2025)-count(" + country + ",2020)",
                    List.of("paper-" + country + "-2025", "paper-" + country + "-2020"),
                    String.valueOf(end - start), "篇"));
        }
        int chinaTotal = verified.papers().stream().filter(p -> "CN".equals(p.country())).toList().size();
        int americaTotal = verified.papers().stream().filter(p -> "US".equals(p.country())).toList().size();
        calculations.add(new Calculation("paper-country-gap", "difference", "CN first-author count - US first-author count",
                List.of("paper-CN", "paper-US"), String.valueOf(chinaTotal - americaTotal), "篇"));
        List<String> parameterIds = verified.parameters().stream().map(FrozenSnapshot.Parameter::id).sorted().toList();
        calculations.add(new Calculation("parameter-records", "count distinct decision_id",
                "COUNT(DISTINCT decision_id) WHERE review_status=REVIEWED AND page, excerpt, name present",
                parameterIds, String.valueOf(parameterIds.size()), "条"));
        List<String> parameterPaperIds = verified.parameters().stream().map(FrozenSnapshot.Parameter::docId)
                .distinct().sorted().toList();
        calculations.add(new Calculation("parameter-paper-count", "count distinct doc_id",
                "COUNT(DISTINCT doc_id) among verified parameter records", parameterPaperIds,
                String.valueOf(parameterPaperIds.size()), "篇"));
        for (String country : List.of("CN", "US")) {
            List<String> countryParameterIds = verified.parameters().stream().filter(p -> country.equals(p.country()))
                    .map(FrozenSnapshot.Parameter::id).distinct().sorted().toList();
            calculations.add(new Calculation("parameter-" + country, "count distinct decision_id",
                    "COUNT(DISTINCT decision_id) WHERE first_author_country=" + country,
                    countryParameterIds, String.valueOf(countryParameterIds.size()), "条"));
        }
        Map<String, List<String>> byFamily = verified.parameters().stream()
                .filter(p -> !p.family().isBlank())
                .collect(Collectors.groupingBy(FrozenSnapshot.Parameter::family,
                        Collectors.mapping(FrozenSnapshot.Parameter::id, Collectors.toList())));
        List<Map.Entry<String, List<String>>> topFamilies = byFamily.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<String>>>comparingInt(e -> e.getValue().size())
                        .reversed().thenComparing(Map.Entry::getKey)).limit(5).toList();
        for (int index = 0; index < topFamilies.size(); index++) {
            Map.Entry<String, List<String>> entry = topFamilies.get(index);
            calculations.add(new Calculation("parameter-family-" + (index + 1), "count distinct decision_id",
                    "COUNT(DISTINCT decision_id) WHERE parameter_family=" + entry.getKey(),
                    entry.getValue().stream().distinct().sorted().toList(),
                    String.valueOf(entry.getValue().stream().distinct().count()), "条"));
        }
        if (!verified.newsEvents().isEmpty()) {
            List<String> eventIds = verified.newsEvents().stream().map(FrozenSnapshot.NewsEvent::id).sorted().toList();
            calculations.add(new Calculation("verified-news-events", "count distinct event_id",
                    "COUNT(DISTINCT event_id) WHERE original source, date and duplicate checks passed",
                    eventIds, String.valueOf(eventIds.size()), "件"));
        }
        return new CalculationHandoff(verified, List.copyOf(calculations));
    }

    private ReportDocument analysisAgent(String sessionId, String runId, FrozenSnapshot.Snapshot snapshot,
                                         Map<String, String> hashes, Plan plan, CalculationHandoff handoff) {
        Map<String, Calculation> calculations = handoff.calculations().stream()
                .collect(Collectors.toMap(Calculation::id, c -> c));
        String paperSource = snapshot.id() + ":papers";
        String parameterSource = snapshot.id() + ":parameters";
        String manifestSource = snapshot.id() + ":manifest";
        List<Source> sources = new ArrayList<>(List.of(
                new Source(paperSource, "local-snapshot", "低轨论文冻结记录", null,
                        snapshot.folder() + "/papers.csv；doc_id 定位", "已纳入、2020—2025、CN/US 第一作者归属；唯一 doc_id",
                        snapshot.paperHash(), snapshot.createdAt().toString(), "机器核验：文件哈希、行数、唯一键和年份"),
                new Source(parameterSource, "local-snapshot", "低轨论文参数冻结记录", null,
                        snapshot.folder() + "/parameters.csv；decision_id、doc_id 与 page_number 定位",
                        "仅统计 REVIEWED 且有摘录、页码和参数名的记录；不据此比较工程性能",
                        snapshot.parameterHash(), snapshot.createdAt().toString(), "机器核验：文件哈希、唯一键、论文关联与字段完整性"),
                new Source(manifestSource, "local-snapshot", "低轨运行快照清单", null,
                        snapshot.folder() + "/manifest.json", "列明样本、年份、来源和新闻事件准入规则",
                        snapshot.manifestHash(), snapshot.createdAt().toString(), "机器核验：清单及输入文件哈希")));
        for (FrozenSnapshot.NewsEvent event : handoff.verified().newsEvents()) {
            sources.add(new Source(snapshot.id() + ":event:" + event.id(), "external",
                    event.actor() + " " + event.action(), event.originalUrl(),
                    event.originalUrl() + "；事件日期 " + event.eventDate(), event.originalExcerpt(),
                    event.originalSha256(), event.originalFetchedAt(),
                    "机器核验：原文哈希、摘录、主体、日期、数值、单位和重复组"));
        }
        Claim total = numeric("paper-total", "冻结样本包含中美第一作者归属的低轨研究论文 "
                + calculations.get("paper-total").value() + " 篇。", paperSource, calculations.get("paper-total"));
        Claim cn = numeric("paper-CN", "其中第一作者归属中国的论文 "
                + calculations.get("paper-CN").value() + " 篇。", paperSource, calculations.get("paper-CN"));
        Claim us = numeric("paper-US", "第一作者归属美国的论文 "
                + calculations.get("paper-US").value() + " 篇。", paperSource, calculations.get("paper-US"));
        Claim gap = numeric("paper-country-gap", "在这一冻结样本和第一作者归属口径下，中国论文数量比美国多 "
                + calculations.get("paper-country-gap").value() + " 篇。", paperSource,
                calculations.get("paper-country-gap"));
        Claim params = numeric("parameter-records", "通过结构化机器核验的论文参数证据记录 "
                + calculations.get("parameter-records").value() + " 条；该数是证据记录量，不代表技术性能水平。",
                parameterSource, calculations.get("parameter-records"));
        List<Claim> parameterExamples = new ArrayList<>();
        Map<String, FrozenSnapshot.Paper> paperById = handoff.verified().papers().stream()
                .collect(Collectors.toMap(FrozenSnapshot.Paper::docId, p -> p));
        Set<String> exampleFamilies = new HashSet<>();
        for (FrozenSnapshot.Parameter parameter : handoff.verified().parameters()) {
            if (parameter.family().isBlank() || !exampleFamilies.add(parameter.family())) continue;
            String id = snapshot.id() + ":parameter:" + parameter.id();
            FrozenSnapshot.Paper paper = paperById.get(parameter.docId());
            String location = "parameters.csv decision_id=" + parameter.id() + "; doc_id=" + parameter.docId()
                    + "; 第 " + parameter.page() + " 页";
            sources.add(new Source(id, "local-record", parameter.title(),
                    paper == null || paper.doi().isBlank() ? null : "https://doi.org/" + paper.doi(),
                    location, parameter.excerpt(), sha256(parameter.id() + "|" + parameter.excerpt()),
                    snapshot.createdAt().toString(), "机器核验：冻结行与字段完整性；尚未逐页复核 PDF 原文"));
            parameterExamples.add(new Claim("parameter-example-" + parameter.id(),
                    "参数族“" + parameter.family() + "”的记录可定位到《" + parameter.title()
                            + "》的原文摘录及页码；具体条件见来源详情。",
                    List.of(id), null, null, null));
            if (parameterExamples.size() == 3) break;
        }
        List<Row> rows = new ArrayList<>();
        for (int year = 2020; year <= 2025; year++) {
            Calculation china = calculations.get("paper-CN-" + year);
            Calculation america = calculations.get("paper-US-" + year);
            rows.add(new Row(List.of(String.valueOf(year), china.value(), america.value()),
                    List.of(paperSource), List.of(china.id(), america.id())));
        }
        Table yearly = new Table("中美第一作者论文年度分布（篇）", List.of("年份", "中国", "美国"), rows);
        Indicator paperNumber = new Indicator("9", "核心论文数量", List.of(total, cn, us, gap,
                new Claim("paper-interpretation", "这一差异描述样本中的论文产出，不直接代表技术性能、工程产能或整体竞争力。",
                        List.of(paperSource), null, null, null)), null);
        Calculation cnDelta = calculations.get("paper-CN-delta");
        Calculation usDelta = calculations.get("paper-US-delta");
        Indicator trend = new Indicator("11", "研究热度趋势", List.of(
                new Claim("trend-method", "按第一作者国别和发表年统计唯一论文标识；年度序列见表。",
                        List.of(paperSource), null, null, null),
                numeric("cn-delta", "中国第一作者论文样本的首末年数量变化为 "
                        + cnDelta.value() + " 篇。", paperSource, cnDelta),
                numeric("us-delta", "美国第一作者论文样本的首末年数量变化为 "
                        + usDelta.value() + " 篇。", paperSource, usDelta)), yearly);
        List<Claim> parameterParagraphs = new ArrayList<>();
        parameterParagraphs.add(params);
        parameterParagraphs.add(numeric("parameter-paper-count", "这些参数记录关联到 "
                + calculations.get("parameter-paper-count").value() + " 篇样本论文。",
                parameterSource, calculations.get("parameter-paper-count")));
        parameterParagraphs.add(numeric("parameter-CN", "其中中国第一作者归属论文的参数记录 "
                + calculations.get("parameter-CN").value() + " 条。",
                parameterSource, calculations.get("parameter-CN")));
        parameterParagraphs.add(numeric("parameter-US", "美国第一作者归属论文的参数记录 "
                + calculations.get("parameter-US").value() + " 条。",
                parameterSource, calculations.get("parameter-US")));
        parameterParagraphs.add(new Claim("parameter-limit", "现有冻结记录保留论文标识、原文摘录与页码；不同对象、条件和单位的数值不作跨论文性能排序。",
                List.of(parameterSource), null, null, null));
        parameterParagraphs.addAll(parameterExamples);
        List<Row> familyRows = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            Calculation calc = calculations.get("parameter-family-" + index);
            if (calc == null) break;
            String family = calc.formula().substring(calc.formula().lastIndexOf('=') + 1);
            familyRows.add(new Row(List.of(family, calc.value()), List.of(parameterSource), List.of(calc.id())));
        }
        Table familyTable = familyRows.isEmpty() ? null
                : new Table("冻结参数记录最多的参数族（记录条数）", List.of("参数族", "记录"), familyRows);
        Indicator parameters = new Indicator("13", "公开技术参数证据", parameterParagraphs, familyTable);
        List<Dimension> dimensions = new ArrayList<>(List.of(
                new Dimension("技术前瞻性", List.of(parameters)),
                new Dimension("科技创新能力", List.of(paperNumber, trend))));
        Map<String, String> exclusions = new LinkedHashMap<>();
        exclusions.put("战略重要性", "本次冻结样本没有核验过的政策原文");
        if (handoff.verified().newsEvents().isEmpty()) {
            exclusions.put("产业成熟度", "本次冻结样本没有通过原始机构来源、日期和去重检查的工程事件");
            exclusions.put("新闻事件数量", "没有可计入正式汇总的原始机构事件；媒体候选不计数");
        } else {
            Calculation newsCount = calculations.get("verified-news-events");
            List<String> newsSources = handoff.verified().newsEvents().stream()
                    .map(e -> snapshot.id() + ":event:" + e.id()).toList();
            Claim newsClaim = new Claim("verified-news-events",
                    "冻结样本中通过原始机构网页、日期及去重机器核验的工程事件 "
                            + newsCount.value() + " 件。", newsSources, newsCount.id(), newsCount.value(), newsCount.unit());
            dimensions.add(new Dimension("产业成熟度", List.of(new Indicator("23", "工程事件", List.of(newsClaim), null))));
        }
        return new ReportDocument(UUID.randomUUID().toString(), runId, sessionId, "leo",
                "低轨卫星星座网络竞争力分析", "低轨卫星星座网络竞争力分析", snapshot.id(),
                store.nextVersion(sessionId, "leo"), Instant.now(), sources, handoff.calculations(),
                List.of(new Claim("scope", "研究对象为低轨卫星星座网络；论文与参数使用 2020—2025 年冻结样本，国别按第一作者归属。",
                        List.of(manifestSource), null, null, null), total, params), dimensions,
                List.of(new Claim("summary", "本次可复核结果限于冻结论文样本及参数证据覆盖；不能据论文数量或参数记录量推断整体工程能力。",
                        List.of(paperSource, parameterSource), null, null, null)),
                exclusions, hashes, List.of(), List.of(), List.of());
    }

    private Claim numeric(String id, String text, String sourceId, Calculation calculation) {
        return new Claim(id, text, List.of(sourceId), calculation.id(), calculation.value(), calculation.unit());
    }

    private List<String> claimIds(ReportDocument report) {
        List<String> ids = new ArrayList<>();
        report.abstractLines().forEach(c -> ids.add(c.id()));
        report.dimensions().forEach(d -> d.indicators().forEach(i -> i.paragraphs().forEach(c -> ids.add(c.id()))));
        report.summaryLines().forEach(c -> ids.add(c.id()));
        return ids;
    }

    private record Plan(String topic, int yearFrom, int yearTo, List<String> indicatorIds, String snapshotId) {}
    private record PaperHandoff(List<FrozenSnapshot.Paper> papers, List<FrozenSnapshot.Parameter> parameters) {}
    private record NewsHandoff(List<FrozenSnapshot.NewsEvent> events, String exclusionReason) {}
    private record VerifiedHandoff(List<FrozenSnapshot.Paper> papers, List<FrozenSnapshot.Parameter> parameters,
                                   List<FrozenSnapshot.NewsEvent> newsEvents, Map<String, String> rejectedNews) {}
    private record CalculationHandoff(VerifiedHandoff verified, List<Calculation> calculations) {}
}
