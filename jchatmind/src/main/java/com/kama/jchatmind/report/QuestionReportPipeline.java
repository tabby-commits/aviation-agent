package com.kama.jchatmind.report;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.service.SkillService;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.kama.jchatmind.report.ReportDocument.*;

/** Fresh question-specific coordination, evidence, analysis, calculation and citation validation. */
@Service
public class QuestionReportPipeline {
    private static final Pattern NUMBERS = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> STOCK_FIELDS = Set.of("inOrbit", "cumulativeLaunched");
    private static final Set<String> NUMERIC_FIELDS = Set.of("plannedConstellation", "inOrbit", "cumulativeLaunched");
    private static final Set<String> TIERS = Set.of("official", "third-party", "media");
    private static final int MAX_ROUNDS = 2;

    private final ResearchRoleModel roles;
    private final WebEvidenceCollector collector;
    private final SkillService skills;
    private final ReportStore store;
    private final CitationValidator validator;
    private final SseService sse;
    private final String newsKbId;
    private final int parallelism;
    private final Object sseLock = new Object();

    public QuestionReportPipeline(ResearchRoleModel roles, WebEvidenceCollector collector,
                                  SkillService skills, ReportStore store, CitationValidator validator,
                                  SseService sse,
                                  @Value("${jchatmind.report.news-kb-id:}") String newsKbId,
                                  @Value("${jchatmind.report.parallelism:3}") int parallelism) {
        this.roles = roles;
        this.collector = collector;
        this.skills = skills;
        this.store = store;
        this.validator = validator;
        this.sse = sse;
        this.newsKbId = newsKbId;
        this.parallelism = Math.max(1, parallelism);
    }

    /** One actor-status task's outcome; merged on the main thread to keep shared state single-writer. */
    private record TaskOutcome(String taskId, String indicator, String dimension, List<Claim> claims,
                               ReportDocument.StatusCard statusCard, List<ResearchRoleModel.CompareRequest> compareRequests,
                               Map<String, String> excluded, Map<String, WebEvidenceCollector.Evidence> evidence,
                               Map<String, List<String>> usedQuotes) {}

    public ReportDocument run(String sessionId, String question, Consumer<String> progress) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("研究问题不能为空");
        Map<String, String> skillHashes = skillLedger();
        String snapshotId = "request-" + UUID.randomUUID();
        String runId = store.start(sessionId, "research", snapshotId, skillHashes);
        AtomicInteger step = new AtomicInteger();
        try {
            List<ResearchRoleModel.Task> allTasks;
            List<ResearchRoleModel.Actor> actors;
            progress.accept("协调 Agent：按本轮问题拆解主体清单与研究任务");
            String frameworkSkill = invokeSkill(runId, sessionId, step, "coordinator",
                    "competitiveness-framework", question);
            ResearchRoleModel.Plan plan;
            try {
                plan = roles.plan(question, frameworkSkill);
            } catch (Exception e) {
                store.stage(runId, "coordinator", "failed", Map.of("question", question),
                        Map.of("reason", String.valueOf(e.getMessage())));
                throw e;
            }
            actors = plan.actors();
            allTasks = plan.tasks();
            store.stage(runId, "coordinator", "done", Map.of("question", question), plan);
            emit(sessionId, step, "plan", null, "研究计划已生成",
                    Map.of("note", "共 " + actors.size() + " 个主体、" + allTasks.size() + " 个任务",
                            "data", Map.of("actors", actors.stream()
                                            .map(a -> a.name() + "(" + a.country() + ")").toList(),
                                    "tasks", allTasks.stream().map(t -> Map.of(
                                            "id", t.id(), "taskType", t.taskType(),
                                            "indicator", t.indicator(), "question", t.question())).toList(),
                                    "compareAspects", plan.compareAspects() == null ? List.of() : plan.compareAspects())));

            Map<String, String> actorCountries = actors.stream()
                    .collect(Collectors.toMap(ResearchRoleModel.Actor::id, ResearchRoleModel.Actor::country,
                            (a, b) -> a, LinkedHashMap::new));
            List<ResearchRoleModel.Task> statusTasks = allTasks.stream()
                    .filter(t -> "actor-status".equals(t.taskType())).toList();

            Map<String, WebEvidenceCollector.Evidence> allEvidence = new LinkedHashMap<>();
            Map<String, List<String>> usedQuotes = new HashMap<>();
            Map<String, String> excluded = new LinkedHashMap<>();
            List<Claim> verifiedClaims = new ArrayList<>();
            // Cards merge by actor so two tasks for one actor never yield duplicate fields.
            Map<String, ReportDocument.StatusCard> cardsByActor = new LinkedHashMap<>();
            List<ResearchRoleModel.CompareRequest> compareRequests = new ArrayList<>();
            Set<String> taskIds = new LinkedHashSet<>();

            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(parallelism, Math.max(1, statusTasks.size())));
            try {
                List<CompletableFuture<TaskOutcome>> futures = new ArrayList<>();
                for (ResearchRoleModel.Task task : statusTasks) {
                    if (!taskIds.add(task.id())) throw new IllegalStateException("协调 Agent 返回重复任务 ID");
                    futures.add(CompletableFuture.supplyAsync(
                            () -> processStatusTask(runId, sessionId, question, step, task,
                                    actors.stream().filter(a -> a.id().equals(task.actorId()))
                                            .findFirst().orElse(null)),
                            executor));
                }
                for (CompletableFuture<TaskOutcome> future : futures) {
                    TaskOutcome outcome = future.join();
                    allEvidence.putAll(outcome.evidence());
                    outcome.usedQuotes().forEach((id, quotes) -> usedQuotes
                            .computeIfAbsent(id, key -> new ArrayList<>()).addAll(quotes));
                    excluded.putAll(outcome.excluded());
                    verifiedClaims.addAll(outcome.claims());
                    if (outcome.statusCard() != null && !outcome.statusCard().fields().isEmpty())
                        mergeCard(cardsByActor, outcome.statusCard());
                    compareRequests.addAll(outcome.compareRequests());
                }
            } finally {
                executor.shutdown();
            }
            List<ReportDocument.StatusCard> statusCards = new ArrayList<>(cardsByActor.values());

            if (verifiedClaims.isEmpty()) throw new IllegalStateException("本轮未取得通过原文核对的证据，未生成报告");
            if (question.contains("中国") && question.contains("美国")) {
                boolean china = statusTasks.stream().anyMatch(t -> actorCountries.getOrDefault(t.actorId(), "")
                        .equals("CN") && verifiedClaims.stream().anyMatch(c -> c.id().startsWith(t.id() + "-")));
                boolean america = statusTasks.stream().anyMatch(t -> actorCountries.getOrDefault(t.actorId(), "")
                        .equals("US") && verifiedClaims.stream().anyMatch(c -> c.id().startsWith(t.id() + "-")));
                if (!china || !america)
                    throw new IllegalStateException("中美比较缺少一侧通过原文核对的证据，本轮不发布比较报告");
            }

            List<Dimension> dimensions = new ArrayList<>();
            Map<String, List<Indicator>> indicatorsByDimension = new LinkedHashMap<>();
            List<Claim> actorClaimsInOrder = verifiedClaims.stream().toList();
            for (ResearchRoleModel.Task task : statusTasks) {
                List<Claim> taskClaims = actorClaimsInOrder.stream()
                        .filter(c -> c.id().startsWith(task.id() + "-")).toList();
                if (taskClaims.isEmpty()) continue;
                indicatorsByDimension.computeIfAbsent(task.dimension(), key -> new ArrayList<>())
                        .add(new Indicator(task.id(), task.indicator(), taskClaims, null));
            }

            // Calculation layer: LLM proposes formulas, Java computes deterministically.
            List<Calculation> calculations = new ArrayList<>();
            progress.accept("计算层：按已核验字段执行同口径对比计算");
            if (!compareRequests.isEmpty() && !statusCards.isEmpty()) {
                List<ResearchRoleModel.CalcPlanItem> planItems;
                try {
                    ResearchRoleModel.ComparePlan comparePlan = roles.compare(compareRequests, statusCards);
                    planItems = comparePlan.calculations();
                    store.stage(runId, "calculator", "done",
                            Map.of("requests", compareRequests.size()), comparePlan);
                } catch (Exception e) {
                    planItems = List.of();
                    store.stage(runId, "calculator", "failed",
                            Map.of("requests", compareRequests.size()),
                            Map.of("reason", String.valueOf(e.getMessage())));
                }
                int index = 0;
                for (ResearchRoleModel.CalcPlanItem item : planItems) {
                    index++;
                    Calculation calculation = compute("calc-" + index, item, statusCards);
                    if (calculation == null) continue;
                    calculations.add(calculation);
                    emit(sessionId, step, "calculation", null, "计算：" + calculation.formula()
                                    + " = " + calculation.value() + calculation.unit(),
                            Map.of("note", calculation.formula() + " = " + calculation.value()
                                    + calculation.unit(), "data", Map.of("id", calculation.id(),
                                    "method", calculation.method(), "inputs", calculation.inputIds(),
                                    "note", item.note() == null ? "" : item.note())));
                }
            }
            store.stage(runId, "deterministic-calculation", "done",
                    Map.of("calculationRequests", compareRequests.size()),
                    Map.of("calculationIds", calculations.stream().map(Calculation::id).toList()));

            // 全指标研判：四个维度各由一个模块分析 Agent 对本维度全部指标做最佳努力分析。
            Map<String, Claim> claimById = verifiedClaims.stream()
                    .collect(Collectors.toMap(Claim::id, c -> c, (a, b) -> a, LinkedHashMap::new));
            Map<String, Calculation> calculationById = calculations.stream()
                    .collect(Collectors.toMap(Calculation::id, c -> c, (a, b) -> a));
            Map<String, List<String>> fieldSourceIds = fieldSourceIndex(statusCards);
            String methodsSkill = invokeSkill(runId, sessionId, step, "dimension-analysis",
                    "ci-analysis-methods", question);
            List<ResearchRoleModel.DimensionAnalysis> modules = new ArrayList<>();
            for (String dimension : List.of("战略重要性", "技术前瞻性", "科技创新能力", "产业成熟度")) {
                progress.accept("模块分析 Agent：研判维度“" + dimension + "”的全部指标");
                try {
                    ResearchRoleModel.DimensionAnalysis module = roles.analyzeDimension(
                            question, dimension, frameworkSkill, verifiedClaims, statusCards,
                            calculations, excluded, methodsSkill);
                    modules.add(module);
                    store.stage(runId, "dimension:" + dimension, "done",
                            Map.of("dimension", dimension),
                            Map.of("assessments", module.assessments().stream()
                                    .map(a -> a.indicator() + "[" + a.evidenceState() + "]").toList()));
                    emit(sessionId, step, "dimension_analysis", null,
                            "模块分析：" + dimension + " " + module.assessments().size() + " 项指标研判完成",
                            Map.of("note", dimension + "：" + module.assessments().size() + " 项",
                                    "data", Map.of("dimension", dimension,
                                            "states", module.assessments().stream()
                                                    .map(a -> a.indicator() + ":" + a.evidenceState()).toList())));
                } catch (Exception e) {
                    excluded.put(dimension, "维度模块分析输出无效：" + e.getMessage());
                    store.stage(runId, "dimension:" + dimension, "failed",
                            Map.of("dimension", dimension),
                            Map.of("reason", String.valueOf(e.getMessage())));
                }
            }
            store.stage(runId, "dimension-analysis", "done",
                    Map.of("modules", modules.size()),
                    Map.of("dimensions", modules.stream()
                            .map(m -> m.dimension() + ":" + m.assessments().size()).toList()));

            // 写作 Agent：从全指标研判底稿中择优采用，输出正文、摘要、研判与差距矩阵。
            progress.accept("写作 Agent：择优采用指标并汇总成文");
            String synthesisSkill = invokeSkill(runId, sessionId, step, "synthesis",
                    "ci-analysis-methods", question);
            ResearchRoleModel.WriterDraft draft;
            try {
                draft = roles.writeReport(question, modules, verifiedClaims, statusCards,
                        calculations, excluded, synthesisSkill);
            } catch (Exception e) {
                draft = null;
                excluded.put("写作Agent", "写作 Agent 输出无效，报告降级为仅含已核验结论：" + e.getMessage());
                store.stage(runId, "synthesis", "failed", Map.of("modules", modules.size()),
                        Map.of("reason", String.valueOf(e.getMessage())));
            }
            List<Claim> writerClaims = new ArrayList<>();
            List<String> writerRejected = new ArrayList<>();
            if (draft != null) {
                for (ResearchRoleModel.WriterIndicator item : draft.indicators().stream().limit(12).toList()) {
                    if (item == null || item.text() == null || item.text().isBlank()
                            || item.indicator() == null || item.indicator().isBlank()) continue;
                    List<String> basis = item.basisClaimIds() == null ? List.of() : item.basisClaimIds();
                    List<String> sourceIds = resolveBasisSources(basis, claimById, calculationById, fieldSourceIds);
                    if (sourceIds.isEmpty()) {
                        writerRejected.add(item.indicator() + "：正文依据不可解析");
                        continue;
                    }
                    // 正文中的数字必须能回溯到依据材料（已核验结论/卡片/计算），否则弃用该指标
                    StringBuilder basisPool = new StringBuilder();
                    for (String ref : basis) {
                        Claim claim = claimById.get(ref);
                        if (claim != null) basisPool.append(' ').append(claim.text().replace(",", ""));
                        Calculation calc = calculationById.get(ref);
                        if (calc != null) basisPool.append(' ').append(calc.formula()).append(' ')
                                .append(calc.value()).append(calc.unit());
                        List<String> cardSources = fieldSourceIds.get(ref);
                        if (cardSources != null) basisPool.append(' ').append(ref);
                    }
                    for (ReportDocument.StatusCard card : statusCards)
                        for (ReportDocument.CardField field : card.fields())
                            basisPool.append(' ').append(field.value().replace(",", ""));
                    Matcher numbers = NUMBERS.matcher(item.text());
                    boolean unsupported = false;
                    while (numbers.find())
                        if (!basisPool.toString().contains(numbers.group().replace(",", ""))) {
                            unsupported = true;
                            break;
                        }
                    if (unsupported) {
                        writerRejected.add(item.indicator() + "：正文含无法回溯的数字");
                        continue;
                    }
                    Claim claim = new Claim("ind-" + UUID.randomUUID(), item.text(),
                            sourceIds, null, null, null);
                    writerClaims.add(claim);
                    claimById.put(claim.id(), claim);
                    indicatorsByDimension.computeIfAbsent(
                                    item.dimension() == null ? "综合分析" : item.dimension(), key -> new ArrayList<>())
                            .add(0, new Indicator("w-" + indicatorsByDimension.size(), item.indicator(),
                                    List.of(claim), null));
                }
                for (String dropped : draft.droppedIndicators() == null ? List.<String>of() : draft.droppedIndicators())
                    excluded.put("未采用：" + dropped, "写作 Agent 判定材料不足或不适用");
                verifiedClaims.addAll(writerClaims);
                store.stage(runId, "writer", "done",
                        Map.of("modules", modules.size()),
                        Map.of("acceptedIndicators", writerClaims.stream()
                                        .map(c -> c.text().length() > 40 ? c.text().substring(0, 40) : c.text()).toList(),
                                "rejected", writerRejected, "dropped", draft.droppedIndicators()));
                emit(sessionId, step, "writer", null,
                        "写作完成：采用 " + writerClaims.size() + " 项指标、弃用 "
                                + (draft.droppedIndicators() == null ? 0 : draft.droppedIndicators().size()) + " 项",
                        Map.of("note", "采用 " + writerClaims.size() + " 项；拒 " + writerRejected.size() + " 项",
                                "data", Map.of("rejected", writerRejected)));
            }
            indicatorsByDimension.forEach((name, indicators) ->
                    dimensions.add(new Dimension(name, List.copyOf(indicators))));

            List<Claim> abstractLines;
            List<Claim> summaryLines;
            List<Claim> overallLines;
            List<ReportDocument.GapRow> gapMatrix = new ArrayList<>();
            if (draft != null) {
                abstractLines = synthesisClaims("abstract", draft.abstractLines(),
                        verifiedClaims, statusCards, calculations);
                summaryLines = synthesisClaims("summary", draft.summaryLines(),
                        verifiedClaims, statusCards, calculations);
                overallLines = synthesisClaims("overall", draft.overallAssessment(),
                        verifiedClaims, statusCards, calculations);
                for (ResearchRoleModel.GapRow row : draft.gapMatrix().stream().limit(6).toList()) {
                    String failure = gapRowFailure(row, verifiedClaims, statusCards, calculations);
                    if (failure != null) {
                        store.stage(runId, "gap-matrix-reject", "done", Map.of("aspect", row.aspect()),
                                Map.of("reason", failure));
                        continue;
                    }
                    gapMatrix.add(new ReportDocument.GapRow(row.aspect(), row.usStatus(), row.cnStatus(),
                            row.gapStatement(), row.evidenceStrength(), row.basisClaimIds()));
                }
            } else {
                abstractLines = List.of();
                summaryLines = List.of();
                overallLines = List.of();
            }
            if (abstractLines.isEmpty()) abstractLines = verifiedClaims.stream().limit(2).toList();
            if (summaryLines.isEmpty()) summaryLines = verifiedClaims.stream()
                    .skip(Math.max(0, verifiedClaims.size() - 2)).toList();
            store.stage(runId, "synthesis", "done", Map.of("question", question,
                            "verifiedClaimIds", verifiedClaims.stream().map(Claim::id).toList()),
                    Map.of("abstract", abstractLines, "summary", summaryLines,
                            "overall", overallLines, "gapMatrix", gapMatrix,
                            "excludedIndicators", excluded));
            emit(sessionId, step, "synthesis", null,
                    "汇总完成：差距矩阵 " + gapMatrix.size() + " 行，整体研判 " + overallLines.size() + " 条",
                    Map.of("note", "差距矩阵 " + gapMatrix.size() + " 行；整体研判 " + overallLines.size() + " 条",
                            "data", Map.of("aspects", gapMatrix.stream()
                                    .map(ReportDocument.GapRow::aspect).toList())));

            Set<String> cited = new LinkedHashSet<>();
            for (Claim claim : verifiedClaims) cited.addAll(claim.sourceIds());
            for (ReportDocument.StatusCard card : statusCards)
                for (ReportDocument.CardField field : card.fields()) cited.add(field.sourceId());
            List<Source> sources = new ArrayList<>();
            for (String id : cited) {
                WebEvidenceCollector.Evidence item = allEvidence.get(id);
                if (item == null) continue;
                String excerpt = String.join(" … ", usedQuotes.getOrDefault(id, List.of()));
                sources.add(new Source(id, "external", item.title(), item.url(),
                        item.rawPath(), excerpt.length() > 1200 ? excerpt.substring(0, 1200) : excerpt,
                        item.sha256(), item.fetchedAt(),
                        "机器核验：本轮获取网页、保存哈希，并逐字核对引用片段；发布主体及报道事实未作人工核验"));
            }
            ReportDocument report = new ReportDocument(UUID.randomUUID().toString(), runId, sessionId,
                    "research", plan.title(), question, snapshotId,
                    store.nextVersion(sessionId, "research"), Instant.now(), List.copyOf(sources),
                    List.copyOf(calculations), abstractLines, List.copyOf(dimensions), summaryLines,
                    excluded, skillHashes, List.copyOf(statusCards), List.copyOf(gapMatrix),
                    List.copyOf(overallLines));
            progress.accept("引用检查：验证每条结论和来源定位");
            boolean degraded = false;
            try {
                validator.validate(report);
            } catch (IllegalStateException e) {
                // A full-report failure must not discard the whole verified run; retry without
                // the LLM-assembled cards and card-derived calculations, then gate again.
                degraded = true;
                store.stage(runId, "citation-check", "failed", Map.of("claimCount", verifiedClaims.size()),
                        Map.of("reason", String.valueOf(e.getMessage())));
                excluded.put("完整性降级", "终检未通过（" + e.getMessage()
                        + "），本版移除现状卡片与对比计算，仅保留通过核验的文字结论");
                report = degradedReport(report, excluded);
                validator.validate(report);
            }
            store.stage(runId, "citation-check", "done", Map.of("claimCount", verifiedClaims.size()),
                    Map.of("sourceCount", sources.size(), "status", degraded ? "degraded" : "passed"));
            store.save(report);
            store.finish(runId, "completed", null);
            emit(sessionId, step, "done", null, degraded ? "报告已生成（降级版）" : "报告已生成",
                    Map.of("note", "第 " + report.version() + " 版报告已生成" + (degraded
                            ? "；终检未通过部分已移除，详见排除说明" : ""), "data",
                            Map.of("reportId", report.id(), "version", report.version())));
            progress.accept("本轮报告已生成");
            return report;
        } catch (Exception e) {
            store.finish(runId, "failed", e.getMessage());
            throw e;
        }
    }

    /** One actor-status task: evidence rounds, analysis, verification, and at most one recollection. */
    private TaskOutcome processStatusTask(String runId, String sessionId, String question,
                                          AtomicInteger step, ResearchRoleModel.Task task,
                                          ResearchRoleModel.Actor actor) {
        List<String> aliases = actor == null || actor.aliases() == null ? List.of() : actor.aliases();
        Map<String, WebEvidenceCollector.Evidence> evidence = new LinkedHashMap<>();
        Map<String, List<String>> usedQuotes = new HashMap<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        int round = 0;
        List<WebEvidenceCollector.Retrieval> retrievals = new ArrayList<>();
        while (round < MAX_ROUNDS) {
            round++;
            if (round == 1) {
                invokeSkill(runId, sessionId, step, "evidence:" + task.id(),
                        "data-resource-rules", task.question());
                if (!newsKbId.isBlank()) {
                    List<WebEvidenceCollector.Evidence> clues = collector.collectFromKnowledgeBase(
                            runId, newsKbId, task.question(), seenUrls, failures);
                    for (WebEvidenceCollector.Evidence clue : clues) evidence.put(clue.id(), clue);
                    emit(sessionId, step, "source", task.id(),
                            "知识库线索：" + clues.size() + " 个原始网页待抓取",
                            Map.of("note", "知识库线索命中 " + clues.size() + " 个 URL",
                                    "data", Map.of("urls", clues.stream()
                                            .map(WebEvidenceCollector.Evidence::url).toList())));
                }
            }
            List<String> queries = round == 1
                    ? task.searchQueries()
                    : recollectQueries(task, aliases, evidence.values().stream().toList());
            progressQuiet(sessionId, "证据 Agent（第 " + round + " 轮）：" + task.question());
            WebEvidenceCollector.Retrieval retrieval = collector.collect(runId, queries,
                    4, 12, java.time.Duration.ofSeconds(180), round == 1 ? null : newsKbId);
            retrievals.add(retrieval);
            retrieval.evidence().forEach(item -> evidence.put(item.id(), item));
            retrieval.searches().forEach(search -> emit(sessionId, step, "search", task.id(),
                    "检索：" + search.query() + "（" + search.hits().size() + " 条命中）",
                    Map.of("note", search.query() + "：" + search.hits().size() + " 条命中", "data",
                            Map.of("query", search.query(), "degraded", search.degraded()))));
            retrieval.evidence().forEach(item -> emit(sessionId, step, "source", task.id(),
                    "已抓取原文：" + item.title(),
                    Map.of("note", "已保存 " + item.url(), "data",
                            Map.of("url", item.url(), "title", item.title()))));
            if (evidence.isEmpty()) {
                excluded.put(task.indicator(), "本轮检索未取得可回查的原始网页正文");
                store.stage(runId, "evidence:" + task.id(), "done", task,
                        Map.of("rounds", round, "failures", failures,
                                "retrievalFailures", retrieval.failures()));
                return new TaskOutcome(task.id(), task.indicator(), task.dimension(), List.of(), null,
                        List.of(), excluded, evidence, usedQuotes);
            }
            String methodsSkill = invokeSkill(runId, sessionId, step, "analysis:" + task.id(),
                    "ci-analysis-methods", task.question());
            String sourceSkill = invokeSkill(runId, sessionId, step, "analysis:" + task.id(),
                    "data-resource-rules", task.question());
            ResearchRoleModel.TaskDraft draft;
            try {
                draft = roles.analyze(question, task, List.copyOf(evidence.values()), sourceSkill, methodsSkill);
            } catch (Exception e) {
                store.stage(runId, "analysis:" + task.id(), "failed", task,
                        Map.of("reason", String.valueOf(e.getMessage())));
                excluded.put(task.indicator(), "分析角色输出无效：" + e.getMessage());
                return new TaskOutcome(task.id(), task.indicator(), task.dimension(), List.of(), null,
                        List.of(), excluded, evidence, usedQuotes);
            }
            emit(sessionId, step, "analysis_draft", task.id(),
                    "分析草稿：" + draft.claims().size() + " 条结论"
                            + (draft.missingEvidence().isEmpty() ? "" : "，缺证据 " + draft.missingEvidence().size() + " 项"),
                    Map.of("note", draft.claims().size() + " 条结论草稿；缺证据 "
                            + draft.missingEvidence().size() + " 项", "data",
                            Map.of("claims", draft.claims().size(),
                                    "missingEvidence", draft.missingEvidence())));

            List<Claim> claims = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            for (ResearchRoleModel.DraftClaim candidate : draft.claims().stream().limit(6).toList()) {
                String reason = rejection(candidate, evidence);
                if (reason != null) {
                    rejected.add(reason);
                    continue;
                }
                List<String> sourceIds = candidate.evidence().stream()
                        .map(ResearchRoleModel.Quote::sourceId).distinct().toList();
                for (ResearchRoleModel.Quote quote : candidate.evidence())
                    usedQuotes.computeIfAbsent(quote.sourceId(), id -> new ArrayList<>()).add(quote.text());
                claims.add(new Claim(task.id() + "-" + UUID.randomUUID(),
                        annotateKind(candidate.text(), candidate.kind()), sourceIds, null, null, null));
            }
            ReportDocument.StatusCard statusCard = verifyCard(draft.statusCard(), evidence, task.actorId(),
                    actor == null ? task.indicator() : actor.name(), actor == null ? null : actor.country());
            store.stage(runId, "fact-verification:" + task.id(), "done",
                    Map.of("candidateClaims", draft.claims().size(), "round", round),
                    Map.of("acceptedClaimIds", claims.stream().map(Claim::id).toList(),
                            "rejected", rejected,
                            "cardFields", statusCard == null ? 0 : statusCard.fields().size()));
            emit(sessionId, step, "verification", task.id(),
                    "核验：" + claims.size() + " 条通过、" + rejected.size() + " 条被拒"
                            + (statusCard == null ? "" : "；现状卡片 " + statusCard.fields().size() + " 个字段"),
                    Map.of("note", claims.size() + " 条通过、" + rejected.size() + " 条被拒",
                            "data", Map.of("rejected", rejected)));
            // Recollect once when the model reports missing evidence; otherwise this task is finished.
            if (draft.missingEvidence().isEmpty() || round >= MAX_ROUNDS) {
                if (claims.isEmpty() && statusCard == null) {
                    excluded.put(task.indicator(), "网页已获取，但模型引用未通过逐项原文与数值核对"
                            + (draft.missingEvidence().isEmpty() ? ""
                            : "；缺口：" + String.join("；", draft.missingEvidence())));
                }
                if (round > 1) {
                    store.stage(runId, "recollect:" + task.id(), "done",
                            Map.of("rounds", round), Map.of("queries", queries));
                    emit(sessionId, step, "recollect", task.id(),
                            "补检索完成（第 " + round + " 轮）",
                            Map.of("note", "第 " + round + " 轮采集 " + retrieval.evidence().size() + " 页",
                                    "data", Map.of("queries", queries)));
                }
                return new TaskOutcome(task.id(), task.indicator(), task.dimension(), claims, statusCard,
                        draft.compareRequests(), excluded, evidence, usedQuotes);
            }
        }
        return new TaskOutcome(task.id(), task.indicator(), task.dimension(), List.of(), null,
                List.of(), excluded, evidence, usedQuotes);
    }

    private List<String> recollectQueries(ResearchRoleModel.Task task, List<String> aliases,
                                          List<WebEvidenceCollector.Evidence> existing) {
        List<String> queries = new ArrayList<>();
        String firstAlias = aliases.isEmpty() ? task.indicator() : aliases.get(0);
        queries.add(firstAlias + " 官方 发布 2026");
        if (aliases.size() > 1) queries.add(aliases.get(1) + " eoportal constellation");
        queries.add(task.question().length() > 40 ? task.question().substring(0, 40) : task.question());
        return queries.stream().distinct().toList();
    }

    private ReportDocument.StatusCard verifyCard(ResearchRoleModel.StatusCard draft,
                                                 Map<String, WebEvidenceCollector.Evidence> evidence,
                                                 String actorId, String actorName, String country) {
        if (draft == null || draft.fields() == null || draft.fields().isEmpty()) return null;
        List<ReportDocument.CardField> fields = new ArrayList<>();
        Set<String> acceptedNames = new HashSet<>();
        for (ResearchRoleModel.CardField field : draft.fields().stream().limit(8).toList()) {
            if (field == null || field.field() == null || field.value() == null
                    || field.value().isBlank()) continue;
            WebEvidenceCollector.Evidence source = field.sourceId() == null ? null : evidence.get(field.sourceId());
            if (source == null || field.quote() == null || field.quote().length() < 8
                    || !normalized(source.text()).contains(normalized(field.quote())))
                continue;
            String name = field.field();
            if (NUMERIC_FIELDS.contains(name)) {
                String digits = field.value().replace(",", "").replace("+", "").trim();
                if (!digits.matches("\\d+(\\.\\d+)?")) continue;
            }
            if (STOCK_FIELDS.contains(name) && (field.asOfDate() == null || !DATE.matcher(field.asOfDate()).matches()))
                continue;
            String tier = field.tier() == null ? "third-party" : field.tier().toLowerCase(Locale.ROOT);
            if (!TIERS.contains(tier)) continue;
            // One verified value per field name; the first occurrence that passes wins.
            if (!acceptedNames.add(name)) continue;
            fields.add(new ReportDocument.CardField(name, field.value().trim(),
                    field.asOfDate(), tier, field.sourceId(), field.quote()));
        }
        return fields.isEmpty() ? null : new ReportDocument.StatusCard(actorId, actorName, country,
                List.copyOf(fields));
    }

    /** Keeps one card per actor; later tasks may add field names the first card did not verify. */
    private void mergeCard(Map<String, ReportDocument.StatusCard> cardsByActor,
                           ReportDocument.StatusCard incoming) {
        ReportDocument.StatusCard existing = cardsByActor.get(incoming.actorId());
        if (existing == null) {
            cardsByActor.put(incoming.actorId(), incoming);
            return;
        }
        List<ReportDocument.CardField> merged = new ArrayList<>(existing.fields());
        Set<String> names = merged.stream().map(ReportDocument.CardField::field)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (ReportDocument.CardField field : incoming.fields())
            if (names.add(field.field())) merged.add(field);
        cardsByActor.put(incoming.actorId(), new ReportDocument.StatusCard(existing.actorId(),
                existing.actorName(), existing.country(), List.copyOf(merged)));
    }

    /** Salvage view of a rejected report: claim-only content, no cards, calculations or tables. */
    ReportDocument degradedReport(ReportDocument report, Map<String, String> excluded) {
        List<Claim> abstractLines = calcFree(report.abstractLines());
        List<Claim> summaryLines = calcFree(report.summaryLines());
        List<Claim> overallLines = calcFree(report.overallAssessment());
        List<Dimension> dimensions = new ArrayList<>();
        Set<String> keptIds = new LinkedHashSet<>();
        for (Dimension dimension : report.dimensions()) {
            List<Indicator> indicators = new ArrayList<>();
            for (Indicator indicator : dimension.indicators()) {
                List<Claim> paragraphs = calcFree(indicator.paragraphs());
                if (paragraphs.isEmpty()) continue;
                paragraphs.forEach(claim -> keptIds.add(claim.id()));
                indicators.add(new Indicator(indicator.code(), indicator.name(), paragraphs, null));
            }
            if (!indicators.isEmpty()) dimensions.add(new Dimension(dimension.name(), List.copyOf(indicators)));
        }
        abstractLines.forEach(claim -> keptIds.add(claim.id()));
        summaryLines.forEach(claim -> keptIds.add(claim.id()));
        overallLines.forEach(claim -> keptIds.add(claim.id()));
        List<ReportDocument.GapRow> gapMatrix = report.gapMatrix().stream()
                .filter(gap -> keptIds.containsAll(gap.basisClaimIds())).toList();
        return new ReportDocument(report.id(), report.runId(), report.sessionId(), report.topic(),
                report.title(), report.question(), report.snapshotId(), report.version(), report.createdAt(),
                report.sources(), List.of(), abstractLines, List.copyOf(dimensions), summaryLines,
                excluded, report.skillHashes(), List.of(), List.copyOf(gapMatrix), overallLines);
    }

    private List<Claim> calcFree(List<Claim> claims) {
        return claims.stream().filter(claim -> claim.calculationId() == null).toList();
    }

    /** 依据引用（已核验结论 id、卡片字段引用、计算 id）解析为可引用来源列表。 */
    private List<String> resolveBasisSources(List<String> refs, Map<String, Claim> claimById,
                                             Map<String, Calculation> calculationById,
                                             Map<String, List<String>> fieldSourceIds) {
        List<String> sources = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) continue;
            Claim claim = claimById.get(ref);
            if (claim != null) sources.addAll(claim.sourceIds());
            Calculation calc = calculationById.get(ref);
            if (calc != null) sources.addAll(calc.inputIds().stream()
                    .flatMap(input -> fieldSourceIds.getOrDefault(input, List.of()).stream()).toList());
            sources.addAll(fieldSourceIds.getOrDefault(ref, List.of()));
        }
        return sources.stream().distinct().toList();
    }

    private Map<String, List<String>> fieldSourceIndex(List<ReportDocument.StatusCard> cards) {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields())
                index.put(card.actorId() + "." + field.field(),
                        List.of(field.sourceId()));
        return index;
    }

    /** Deterministic execution of a proposed comparison; returns null when preconditions fail. */
    private Calculation compute(String id, ResearchRoleModel.CalcPlanItem item,
                                List<ReportDocument.StatusCard> cards) {
        Map<String, ReportDocument.CardField> fields = new LinkedHashMap<>();
        Map<String, String> asOf = new LinkedHashMap<>();
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields()) {
                fields.put(card.actorId() + "." + field.field(), field);
                if (field.asOfDate() != null) asOf.put(card.actorId() + "." + field.field(), field.asOfDate());
            }
        ReportDocument.CardField left = fields.get(item.leftRef());
        ReportDocument.CardField right = fields.get(item.rightRef());
        if (left == null || right == null) return null;
        if ("media".equals(left.tier()) || "media".equals(right.tier())) return null;
        long daysApart = 0;
        if (asOf.containsKey(item.leftRef()) && asOf.containsKey(item.rightRef()))
            daysApart = Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(asOf.get(item.leftRef())),
                    LocalDate.parse(asOf.get(item.rightRef()))));
        double leftValue = Double.parseDouble(left.value().replace(",", "").replace("+", "").trim());
        double rightValue = Double.parseDouble(right.value().replace(",", "").replace("+", "").trim());
        String aggregation = item.aggregation();
        if ("ratio".equalsIgnoreCase(aggregation) && daysApart > 90) aggregation = "difference";
        if (daysApart > 365) return null;
        if ("difference".equalsIgnoreCase(aggregation)) {
            long difference = Math.round(leftValue - rightValue);
            return new Calculation(id, "field-difference",
                    item.metric() + "：" + item.leftRef() + " − " + item.rightRef(),
                    List.of(item.leftRef(), item.rightRef()), String.valueOf(difference), item.unit() == null ? "" : item.unit());
        }
        if ("ratio".equalsIgnoreCase(aggregation) && rightValue != 0) {
            double ratio = Math.round(leftValue / rightValue * 10.0) / 10.0;
            return new Calculation(id, "field-ratio",
                    item.metric() + "：" + item.leftRef() + " ÷ " + item.rightRef(),
                    List.of(item.leftRef(), item.rightRef()), ratioText(ratio), "倍");
        }
        return null;
    }

    /** Whole-number ratios read naturally without a trailing .0 in report text. */
    static String ratioText(double ratio) {
        return ratio == Math.rint(ratio) ? String.valueOf((long) ratio) : String.valueOf(ratio);
    }

    private String gapRowFailure(ResearchRoleModel.GapRow row, List<Claim> verified,
                                 List<ReportDocument.StatusCard> cards, List<Calculation> calculations) {
        if (row == null || row.aspect() == null || row.aspect().isBlank()) return "维度名为空";
        if (row.basisClaimIds() == null || row.basisClaimIds().isEmpty()) return "缺少 basisClaimIds";
        Set<String> claimIds = verified.stream().map(Claim::id).collect(Collectors.toSet());
        if (row.basisClaimIds().stream().anyMatch(id -> !claimIds.contains(id))) return "basisClaimIds 不存在";
        String basis = String.join(" ", row.basisClaimIds().stream()
                .filter(claimIds::contains)
                .map(id -> verified.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().text())
                .toList());
        StringBuilder pool = new StringBuilder(basis.replace(",", ""));
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields())
                pool.append(' ').append(field.value().replace(",", ""));
        for (Calculation calculation : calculations)
            pool.append(' ').append(calculation.value().replace(",", ""));
        for (String text : List.of(row.usStatus(), row.cnStatus(), row.gapStatement())) {
            if (text == null) continue;
            Matcher numbers = NUMBERS.matcher(text);
            while (numbers.find())
                if (!pool.toString().contains(numbers.group().replace(",", "")))
                    return "矩阵数字不在已核验证据中：" + numbers.group();
        }
        return null;
    }

    private List<Claim> synthesisClaims(String prefix, List<ResearchRoleModel.SynthesisClaim> drafts,
                                        List<Claim> verified, List<ReportDocument.StatusCard> cards,
                                        List<Calculation> calculations) {
        if (drafts == null) return List.of();
        Map<String, Claim> byId = new HashMap<>();
        verified.forEach(claim -> byId.put(claim.id(), claim));
        StringBuilder numberPool = new StringBuilder();
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields())
                numberPool.append(' ').append(field.value().replace(",", ""));
        for (Calculation calculation : calculations)
            numberPool.append(' ').append(calculation.value().replace(",", ""));
        List<Claim> accepted = new ArrayList<>();
        for (ResearchRoleModel.SynthesisClaim draft : drafts.stream().limit(3).toList()) {
            if (draft == null || draft.text() == null || draft.text().isBlank()
                    || draft.basisClaimIds() == null || draft.basisClaimIds().isEmpty()
                    || draft.basisClaimIds().stream().anyMatch(id -> !byId.containsKey(id))) continue;
        String basis = String.join(" ", draft.basisClaimIds().stream()
                .map(id -> byId.get(id).text()).toList());
        String pool = basis.replace(",", "") + " " + numberPool;
        Matcher numbers = NUMBERS.matcher(draft.text());
        // Inference lines state structure, never quantities; every other number must come from verified material.
        boolean inferenceOnly = "overall".equals(prefix) || "inference".equals(draft.kind());
        boolean unsupported = inferenceOnly && numbers.find();
        while (!unsupported && numbers.find())
            if (!pool.contains(numbers.group().replace(",", ""))) unsupported = true;
        if (unsupported) continue;
            List<String> sources = draft.basisClaimIds().stream()
                    .flatMap(id -> byId.get(id).sourceIds().stream()).distinct().toList();
            if (sources.isEmpty()) continue;
            accepted.add(new Claim(prefix + "-" + UUID.randomUUID(),
                    "inference".equals(draft.kind()) && !draft.text().startsWith("综合判断")
                            ? "综合判断：" + draft.text() : draft.text(),
                    sources, null, null, null));
        }
        return List.copyOf(accepted);
    }

    private String annotateKind(String text, String kind) {
        if ("reported".equals(kind) && !text.contains("转述") && !text.contains("报道"))
            return "（媒体转述）" + text;
        if ("planned".equals(kind) && !text.contains("计划") && !text.contains("规划")
                && !text.contains("拟") && !text.contains("目标"))
            return "（计划节点）" + text;
        return text;
    }

    private String rejection(ResearchRoleModel.DraftClaim candidate,
                             Map<String, WebEvidenceCollector.Evidence> evidence) {
        if (candidate == null || candidate.text() == null || candidate.text().isBlank()
                || candidate.evidence() == null || candidate.evidence().isEmpty()) return "结论或来源为空";
        StringBuilder quotes = new StringBuilder();
        StringBuilder sourceDates = new StringBuilder();
        for (ResearchRoleModel.Quote quote : candidate.evidence()) {
            WebEvidenceCollector.Evidence source = evidence.get(quote.sourceId());
            if (source == null || quote.text() == null || quote.text().length() < 8
                    || !normalized(source.text()).contains(normalized(quote.text())))
                return "引文不在本轮原网页正文中";
            quotes.append(' ').append(quote.text());
            if (source.publishedAt() != null) sourceDates.append(' ').append(source.publishedAt());
        }
        Matcher numbers = NUMBERS.matcher(candidate.text());
        while (numbers.find()) {
            String value = numbers.group().replace(",", "");
            String quoted = quotes.toString();
            boolean direct = quoted.replace(",", "").contains(value)
                    || sourceDates.toString().contains(numbers.group());
            boolean month = candidate.text().substring(numbers.end()).startsWith("月")
                    && monthName(value) != null
                    && quoted.toLowerCase(Locale.ROOT).contains(monthName(value));
            if (!direct && !month) return "结论数值不在引文或网页发布日期中：" + numbers.group();
        }
        return null;
    }

    private String monthName(String value) {
        return switch (value) {
            case "1" -> "january"; case "2" -> "february"; case "3" -> "march";
            case "4" -> "april"; case "5" -> "may"; case "6" -> "june";
            case "7" -> "july"; case "8" -> "august"; case "9" -> "september";
            case "10" -> "october"; case "11" -> "november"; case "12" -> "december";
            default -> null;
        };
    }

    private String normalized(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private void progressQuiet(String sessionId, String text) {
        try {
            synchronized (sseLock) {
                sse.send(sessionId, SseMessage.builder().type(SseMessage.Type.REPORT_STAGE)
                        .payload(SseMessage.Payload.builder().stage("report").statusText(text).done(false).build())
                        .build());
            }
        } catch (Exception ignored) { /* Disconnected browser does not change the run. */ }
    }

    /** Step-level SSE: one event per observable pipeline step, with a trimmed detail payload. */
    private void emit(String sessionId, AtomicInteger step, String toolName, String taskId,
                      String statusText, Map<String, Object> detail) {
        try {
            synchronized (sseLock) {
                sse.send(sessionId, SseMessage.builder().type(SseMessage.Type.REPORT_STEP)
                        .payload(SseMessage.Payload.builder()
                                .stage("report").toolName(toolName).taskId(taskId)
                                .step(step.incrementAndGet()).statusText(statusText)
                                .done("done".equals(toolName))
                                .detail(trim(detail)).build())
                        .build());
            }
        } catch (Exception ignored) { /* Disconnected browser does not change the run. */ }
    }

    private Map<String, Object> trim(Map<String, Object> detail) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : detail.entrySet()) {
            String json = roles.toJson(entry.getValue());
            trimmed.put(entry.getKey(), json.length() > 2000 ? json.substring(0, 2000) + "…" : json);
        }
        return trimmed;
    }

    private Map<String, String> skillLedger() {
        Map<String, String> ledger = new LinkedHashMap<>();
        for (String name : List.of("competitiveness-framework", "data-resource-rules", "ci-analysis-methods")) {
            SkillDefinition skill = skills.getSkillByName(name)
                    .orElseThrow(() -> new IllegalStateException("Required Skill not loaded: " + name));
            ledger.put(name, sha256(skill.getRawContent()));
        }
        return Map.copyOf(ledger);
    }

    private String invokeSkill(String runId, String sessionId, AtomicInteger step, String role,
                               String name, String question) {
        String context = "role=" + role + "；question=" + question;
        String result = skills.invokeSkill(name, context);
        if (result.startsWith("Error:")) throw new IllegalStateException(result);
        String raw = skills.getSkillByName(name).orElseThrow().getRawContent();
        Matcher version = Pattern.compile("(?m)^version:\\s*([^\\s]+)").matcher(raw);
        String skillVersion = version.find() ? version.group(1) : "unknown";
        store.stage(runId, "skill:" + name + ":" + role, "done",
                Map.of("context", context), Map.of("result", result, "sha256", sha256(result),
                        "version", skillVersion));
        emit(sessionId, step, "skill_load", null, "加载 Skill：" + name + " v" + skillVersion,
                Map.of("note", name + " v" + skillVersion + " → " + role,
                        "data", Map.of("skill", name, "version", skillVersion, "role", role)));
        return result;
    }

    private String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
