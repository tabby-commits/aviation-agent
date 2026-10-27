package com.kama.jchatmind.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.skill.service.SkillService;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model-backed role handoffs. Every call uses the current question and current-run evidence. */
@Component
public class ResearchRoleModel {
    private static final Set<String> DIMENSIONS = Set.of("战略重要性", "技术前瞻性", "科技创新能力", "产业成熟度");
    /** Fallback when the framework Skill cannot be parsed; the Skill table is the authoritative list. */
    private static final Set<String> FALLBACK_INDICATORS = Set.of("战略政策强度", "国家安全关联程度", "关键技术管制程度",
            "国家重大项目部署", "头部航天主体布局动向", "范式变革潜力", "核心论文数量", "高被引论文数量与占比",
            "研究热度趋势", "国际合作论文比例与结构", "已公开最高技术指标", "公开技术性能变化", "科研机构贡献",
            "产能水平", "产品迭代速度", "应用拓展能力");
    /** 协调规划允许的尝试次数：首轮 + 5 轮重试。 */
    private static final int MAX_PLAN_ATTEMPTS = 6;
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final SkillService skills;
    private volatile Set<String> frameworkIndicators;

    public ResearchRoleModel(ChatClientRegistry registry, ObjectMapper mapper, SkillService skills,
                             @Value("${jchatmind.report.model:deepseek}") String model) {
        this.client = registry.get(model);
        if (client == null) throw new IllegalStateException("Report model unavailable: " + model);
        this.mapper = mapper;
        this.skills = skills;
    }

    public record Actor(String id, String name, String country, List<String> aliases) {}
    public record Task(String id, String taskType, String actorId, String dimension, String indicator,
                       String question, List<String> searchQueries) {}
    public record Plan(String title, String topic, List<Actor> actors, List<String> compareAspects,
                       List<Task> tasks) {}

    public record CardField(String field, String value, String asOfDate, String tier,
                            String sourceId, String quote) {}
    public record StatusCard(String actorId, List<CardField> fields) {}
    public record Quote(String sourceId, String text) {}
    public record DraftClaim(String text, String kind, List<Quote> evidence) {}
    public record CompareRequest(String metric, String actorA, String actorB, String unit, String aggregation) {}
    public record TaskDraft(StatusCard statusCard, List<DraftClaim> claims, List<String> missingEvidence,
                            List<CompareRequest> compareRequests) {}

    public record SynthesisClaim(String text, String kind, List<String> basisClaimIds) {}
    public record GapRow(String aspect, String usStatus, String cnStatus, String gapStatement,
                         String evidenceStrength, List<String> basisClaimIds) {}
    public record Synthesis(List<GapRow> gapMatrix, List<SynthesisClaim> abstractLines,
                            List<SynthesisClaim> overallAssessment, List<SynthesisClaim> summaryLines) {}

    /** 全指标研判：一个维度下每个指标的最佳努力分析（Sub Agent 输出，供写作 Agent 取材）。 */
    public record IndicatorAssessment(String indicator, String evidenceState, String assessment,
                                      List<String> basisClaimIds) {}
    public record DimensionAnalysis(String dimension, List<IndicatorAssessment> assessments) {}

    /** 写作 Agent 的择优结果：采用的指标正文 + 摘要/总结/研判/差距矩阵 + 丢弃清单。 */
    public record WriterIndicator(String dimension, String indicator, String reason, String text,
                                  List<String> basisClaimIds) {}
    public record WriterDraft(List<WriterIndicator> indicators, List<SynthesisClaim> abstractLines,
                              List<SynthesisClaim> overallAssessment, List<SynthesisClaim> summaryLines,
                              List<GapRow> gapMatrix, List<String> droppedIndicators) {}

    public record CalcPlanItem(String id, String aggregation, String metric, String unit,
                               String leftRef, String rightRef, String note) {}
    public record ComparePlan(List<CalcPlanItem> calculations) {}

    public record CompareClaimDraft(String text, String kind, String calculationId, List<String> basisClaimIds) {}
    public record CompareDraft(List<CompareClaimDraft> claims) {}

    public Plan plan(String question, String frameworkSkill) {
        String result = complete("""
                你是航天竞争情报协调 Agent。仅输出 JSON，不能输出 Markdown。
                任务：把用户问题拆成一份“主体清单 + 主体现状研究子任务”的研究计划。
                四个维度的全指标研判、比较与择优成文由流水线后续的分析 Agent 和写作 Agent 固定执行，
                计划里不需要、也不允许出现比较类任务。

                第一步，先确定主体清单 actors：把问题涉及的代表性主体全部列出（比较类问题两侧各至少
                一个，能多列就多列，例如 Starlink、Kuiper、国网/Guowang、千帆/Qianfan）。每个主体给出
                country（CN/US）与 aliases（中英文检索别名数组）。

                第二步，拆任务 tasks：每个主体必须恰好一个 actor-status（主体现状）任务，question 写
                “该主体截至当前的星座建设现状：规划规模、在轨卫星数、累计发射、最新版本与迭代、
                商业化/组网节点”。这是报告的分项目章节，不允许合并多个主体。

                规则：
                1. tasks 数量 = 主体数量，全部为 actor-status，上限 8；taskType 只有这一种。
                2. dimension 必须是战略重要性、技术前瞻性、科技创新能力、产业成熟度之一；indicator
                   只能以框架 28 项指标的完整名称开头，冒号后写具体对象。发射、部署、回收复用记录归入
                   “产能水平”；版本节点归入“产品迭代速度”；政策与战略事实归入对应政策类指标。
                3. searchQueries：每个任务 2—4 条，必须包含主体官方或权威来源词（如
                   site:spacex.com、eoPortal），中国主体必须含中文检索词；政策类问题加官方来源
                   检索词（如 site:nasa.gov、site:gov.cn）。
                4. 不预设结论。topic 只填 leo、rocket、mixed 或 other。

                JSON 结构：
                {"title":"针对问题的报告标题","topic":"leo",
                 "actors":[{"id":"a1","name":"Starlink","country":"US","aliases":["Starlink","SpaceX"]}],
                 "compareAspects":[],
                 "tasks":[{"id":"t1","taskType":"actor-status","actorId":"a1","dimension":"产业成熟度",
                           "indicator":"产能水平：Starlink","question":"...","searchQueries":["..."]}]}
                今天是 %s。研究只涉及公开可观察的事实，不计算综合排名。
                """.formatted(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                + "\n本轮竞争力框架 Skill：\n" + frameworkSkill, question);
        String error = null;
        for (int attempt = 0; attempt < MAX_PLAN_ATTEMPTS; attempt++) {
            try {
                Plan plan = mapper.readValue(json(result), Plan.class);
                error = planError(plan);
                if (error == null) return plan;
            } catch (Exception e) {
                error = e.getMessage();
            }
            if (attempt < MAX_PLAN_ATTEMPTS - 1) result = complete("你是协调 Agent。只输出修正后的完整 JSON，不输出说明或 Markdown。",
                    "原问题：" + question + "\n上一版计划：" + result + "\n需要修正：" + error
                            + "。要求：actors 覆盖问题涉及的全部代表性主体；tasks 中每个主体恰好一个 "
                            + "taskType=actor-status 的任务（带 actorId），不要输出其他 taskType；dimension 只能是"
                            + "战略重要性、技术前瞻性、科技创新能力、产业成熟度；indicator 必须以下列指标名称之一"
                            + "开头（冒号后写具体对象）：" + String.join("、", frameworkIndicators())
                            + "；每个任务都有 id、taskType、dimension、indicator、question 和 searchQueries。");
        }
        throw new IllegalStateException("协调 Agent 未能形成有效任务计划：" + error);
    }

    private String planError(Plan plan) {
        if (plan.title() == null || plan.title().isBlank()) return "缺少标题";
        if (plan.actors() == null || plan.actors().isEmpty()) return "缺少主体清单 actors";
        Set<String> actorIds = new LinkedHashSet<>();
        for (Actor actor : plan.actors()) {
            if (actor.id() == null || actor.id().isBlank() || actor.name() == null || actor.name().isBlank()
                    || actor.country() == null || actor.country().isBlank())
                return "主体字段缺失：" + actor.id();
            if (!"CN".equals(actor.country()) && !"US".equals(actor.country()))
                return "主体 country 只能是 CN 或 US：" + actor.name();
            if (!actorIds.add(actor.id())) return "主体 ID 重复：" + actor.id();
        }
        if (plan.tasks() == null || plan.tasks().size() < 1 || plan.tasks().size() > 8)
            return "任务数量应为 1—8，实际为 " + (plan.tasks() == null ? 0 : plan.tasks().size());
        Set<String> coveredActors = new LinkedHashSet<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (Task task : plan.tasks()) {
            if (task.id() == null || task.id().isBlank() || task.question() == null || task.question().isBlank()
                    || task.dimension() == null || task.dimension().isBlank()
                    || task.indicator() == null || task.indicator().isBlank()
                    || task.searchQueries() == null || task.searchQueries().isEmpty())
                return "任务字段缺失：" + task.id();
            if (!taskIds.add(task.id())) return "任务 ID 重复：" + task.id();
            String type = task.taskType() == null ? "" : task.taskType();
            if (!"actor-status".equals(type))
                return "taskType 只能是 actor-status：" + task.id();
            if (!DIMENSIONS.contains(task.dimension())) return "一级维度不在四维框架内：" + task.dimension();
            String indicatorName = task.indicator().split("[:：]", 2)[0].trim();
            if (!frameworkIndicators().contains(indicatorName))
                return "二级指标不在框架 28 项指标内：" + task.indicator();
            if (task.actorId() == null || !actorIds.contains(task.actorId()))
                return "actor-status 任务引用了不存在的主体：" + task.id();
            if (!coveredActors.add(task.actorId()))
                return "同一主体出现了多个 actor-status 任务：" + task.actorId();
        }
        if (coveredActors.size() != plan.actors().size())
            return "每个主体必须恰好一个 actor-status 任务";
        return null;
    }

    /**
     * 全指标研判模式下，框架 28 项指标全部允许进入计划；表中的“本阶段状态”列
     * 描述证据要求与边界，不再是准入门槛。Skill 表格仍是权威清单。
     */
    private Set<String> frameworkIndicators() {
        Set<String> cached = frameworkIndicators;
        if (cached != null) return cached;
        Set<String> parsed = new LinkedHashSet<>();
        try {
            String raw = skills.getSkillByName("competitiveness-framework").orElseThrow().getRawContent();
            for (String line : raw.split("\\R")) {
                String[] cells = line.split("\\|");
                if (cells.length < 4) continue;
                String indicator = cells[2].trim();
                if (!indicator.isBlank() && !indicator.equals("指标"))
                    parsed.add(indicator);
            }
        } catch (Exception ignored) { /* Fall back to the built-in list below. */ }
        if (parsed.isEmpty()) parsed.addAll(FALLBACK_INDICATORS);
        frameworkIndicators = parsed;
        return parsed;
    }

    public TaskDraft analyze(String question, Task task, List<WebEvidenceCollector.Evidence> evidence,
                             String sourceSkill, String methodsSkill) {
        StringBuilder packet = new StringBuilder();
        int included = 0;
        for (WebEvidenceCollector.Evidence item : evidence) {
            if (included >= 8) break;
            packet.append("\nSOURCE_ID=").append(item.id()).append("\nURL=").append(item.url())
                    .append("\nTITLE=").append(item.title()).append("\nPUBLISHED=")
                    .append(item.publishedAt()).append("\nORIGINAL_TEXT=\n")
                    .append(item.text(), 0, Math.min(item.text().length(), 6000)).append("\n");
            included++;
        }
        String actorLine = task.actorId() == null ? "" : "本任务的主体编号是 " + task.actorId() + "。";
        String result = complete("""
                你是证据分析 Agent。仅输出 JSON，不输出 Markdown。只使用本轮提供的原网页正文
                （ORIGINAL_TEXT），不使用记忆或常识补事实。

                本任务是主体现状任务时（%s），先填 statusCard，字段及口径——
                - plannedConstellation 规划星座规模（颗）｜inOrbit 在轨卫星数（颗，必须带 asOfDate 基准日）
                - cumulativeLaunched 累计发射卫星数（颗，注明是否含退役）｜launchCadence 当前发射节奏
                - latestVersion 最新卫星版本/代际｜commercial 商业化或组网里程碑
                每个字段：value + asOfDate（仅存量字段需要）+ tier（official=官方来源 /
                third-party=权威第三方汇编 / media=媒体转述）+ sourceId + quote（原文连续 20—120 个
                字符摘录，不翻译、不改写、不加省略号）。同一字段名在 fields 中最多出现一次；
                不同来源数值冲突时只保留 tier 最高的一个，其余写入 missingEvidence。
                原文没有的字段不要填，列入 missingEvidence，
                不要估算。本任务是比较任务时 statusCard 为 null。

                然后写 claims：1—6 条具体、解释性的分析结论，每条附至少一段 quote 并标 kind：
                - fact：原始来源直接陈述的事实；
                - reported：第三方或媒体转述（正文须写明“据××报道/转述”）；
                - planned：计划、意向、未完成节点（不得与 fact 混写）。

                再提 compareRequests（比较类问题需要对比数字时）：声明要比较的 metric、actorA、
                actorB（填主体编号）、unit、aggregation（difference 或 ratio）。你自己不得在结论中写
                “××倍”“多 N 颗”这类计算结果——所有对比数字由计算层统一计算。

                任何数字、日期、单位必须能在所引原文找到；不得加入原文没有的“截至今天/今年”。
                无法证实或来源冲突时返回空 claims 并写明 missingEvidence。不得由报道量推断能力。
                区分计划、试验、实际部署和已实现复用。

                JSON：{"statusCard":{"actorId":"a1","fields":[{"field":"inOrbit","value":"186",
                "asOfDate":"2026-10-24","tier":"third-party","sourceId":"SOURCE_ID","quote":"..."}]},
                "claims":[{"text":"结论句","kind":"fact","evidence":[{"sourceId":"SOURCE_ID","text":"原文摘录"}]}],
                "missingEvidence":["需要××的最新在轨数与基准日"],
                "compareRequests":[{"metric":"在轨卫星数","actorA":"a1","actorB":"a2","unit":"颗",
                "aggregation":"ratio"}]}
                """.formatted("actor-status".equals(task.taskType()))
                + "\n本轮数据源 Skill：\n" + sourceSkill
                + "\n本轮分析方法 Skill：\n" + methodsSkill,
                "用户问题：" + question + "\n子任务：" + task.question() + "\n" + actorLine
                        + "\n原始证据：" + packet);
        try {
            TaskDraft draft = mapper.readValue(json(result), TaskDraft.class);
            return new TaskDraft(draft.statusCard(),
                    draft.claims() == null ? List.of() : draft.claims(),
                    draft.missingEvidence() == null ? List.of() : draft.missingEvidence(),
                    draft.compareRequests() == null ? List.of() : draft.compareRequests());
        } catch (Exception e) {
            throw new IllegalStateException("证据分析 Agent 输出无效：" + task.id(), e);
        }
    }

    public ComparePlan compare(List<CompareRequest> requests, List<ReportDocument.StatusCard> cards) {
        StringBuilder requestText = new StringBuilder();
        for (CompareRequest request : requests)
            requestText.append(request.metric()).append(" | ").append(request.actorA()).append(" vs ")
                    .append(request.actorB()).append(" | ").append(request.unit()).append(" | ")
                    .append(request.aggregation()).append("\n");
        StringBuilder fieldText = new StringBuilder();
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields())
                fieldText.append(card.actorId()).append(".").append(field.field()).append(" = ")
                        .append(field.value()).append(field.asOfDate() == null ? "" : " (基准日 " + field.asOfDate() + ")")
                        .append(" [").append(field.tier()).append("]\n");
        String result = complete("""
                你是计算规划 Agent。仅输出 JSON。根据对比请求和已核验的现状卡片字段，提出本次允许执行的
                计算清单。只允许 difference（同单位两数之差）和 ratio（同单位两数之商）两种运算。规则：
                1. leftRef/rightRef 必须逐字引用已核验字段清单中的“主体编号.字段名”；不得引用未核验数字。
                2. 在轨存量比较：两侧 asOfDate 基准日相差超过 90 天时不出 ratio，只出 difference 并在
                   note 写明两侧基准日；相差超过 365 天时不出任何计算。
                3. 每条计算给 note：口径说明（是否含退役、来源层级）。
                4. 字段清单不支持请求时不产出该计算，宁缺毋滥。unit 填该指标的计量单位（如 颗、次）。
                JSON：{"calculations":[{"id":"calc-1","aggregation":"ratio","metric":"在轨卫星数",
                "unit":"颗","leftRef":"a1.inOrbit","rightRef":"a2.inOrbit",
                "note":"两侧均为第三方汇编存量"}]}
                """,
                "对比请求：\n" + requestText + "\n已核验字段清单：\n" + fieldText);
        try {
            ComparePlan plan = mapper.readValue(json(result), ComparePlan.class);
            return new ComparePlan(plan.calculations() == null ? List.of() : plan.calculations());
        } catch (Exception e) {
            throw new IllegalStateException("计算规划 Agent 输出无效", e);
        }
    }

    public CompareDraft composeCompare(String question, Task task, List<ReportDocument.Calculation> calculations,
                                       List<ReportDocument.StatusCard> cards, List<ReportDocument.Claim> basisClaims,
                                       String methodsSkill) {
        StringBuilder calcText = new StringBuilder();
        for (ReportDocument.Calculation calc : calculations)
            calcText.append(calc.id()).append(" | ").append(calc.formula()).append(" = ")
                    .append(calc.value()).append(" ").append(calc.unit()).append("\n");
        StringBuilder basisText = new StringBuilder();
        for (ReportDocument.Claim claim : basisClaims)
            basisText.append(claim.id()).append(" | ").append(claim.text()).append("\n");
        StringBuilder cardText = new StringBuilder();
        for (ReportDocument.StatusCard card : cards)
            for (ReportDocument.CardField field : card.fields())
                cardText.append(card.actorName()).append(".").append(field.field()).append(" = ")
                        .append(field.value()).append(field.asOfDate() == null ? "" : " (" + field.asOfDate() + ")")
                        .append("\n");
        String result = complete("""
                你是比较分析 Agent。仅输出 JSON。只使用已核验结论、现状卡片和计算结果，不引入新事实。
                针对比较子任务写 1—4 条对比结论，每条标 kind：
                - calc：包含计算层数字的结论，必须给 calculationId，数字必须逐字使用该计算的 value；
                - fact：并列陈述两侧已核验事实（不计算），basisClaimIds 指向支撑的结论编号；
                没有计算结果时只写 fact 类：用“美方已核验证据为…，中方已核验证据为…”的证据差句式。
                不得使用“领先/落后 ×年”这类无法由证据支持的表述；不得自行计算新数字。
                JSON：{"claims":[{"text":"结论句","kind":"calc","calculationId":"calc-1",
                "basisClaimIds":["t1-..."]}]}
                """ + "\n本轮分析方法 Skill：\n" + methodsSkill,
                "用户问题：" + question + "\n比较子任务：" + task.question()
                        + "\n计算结果：\n" + calcText + "\n现状卡片：\n" + cardText
                        + "\n已核验结论：\n" + basisText);
        try {
            CompareDraft draft = mapper.readValue(json(result), CompareDraft.class);
            return new CompareDraft(draft.claims() == null ? List.of() : draft.claims());
        } catch (Exception e) {
            throw new IllegalStateException("比较分析 Agent 输出无效：" + task.id(), e);
        }
    }

    public Synthesis synthesize(String question, List<ReportDocument.Claim> verified,
                                List<ReportDocument.StatusCard> cards, List<ReportDocument.Calculation> calculations,
                                Map<String, String> excluded, String methodsSkill) {
        StringBuilder packet = new StringBuilder();
        verified.forEach(claim -> packet.append(claim.id()).append(" | ").append(claim.text()).append("\n"));
        StringBuilder cardText = new StringBuilder();
        for (ReportDocument.StatusCard card : cards)
            cardText.append(card.actorName()).append("(").append(card.country()).append(")：")
                    .append(card.fields().stream()
                            .map(f -> f.field() + "=" + f.value()
                                    + (f.asOfDate() == null ? "" : "@" + f.asOfDate()) + "[" + f.tier() + "]")
                            .reduce((a, b) -> a + "；" + b).orElse("无字段"))
                    .append("\n");
        StringBuilder calcText = new StringBuilder();
        for (ReportDocument.Calculation calc : calculations)
            calcText.append(calc.id()).append(" | ").append(calc.formula()).append(" = ")
                    .append(calc.value()).append(calc.unit()).append("\n");
        String result = complete("""
                你是报告汇总 Agent。仅输出 JSON，不输出 Markdown。基于已核验结论、现状卡片和计算结果写四部分：

                1. gapMatrix（差距矩阵，存在比较任务时必填，否则为空数组）：每个比较维度一行——
                   aspect（维度名）、usStatus（美国侧已核验事实，含数字与基准日）、cnStatus（同上）、
                   gapStatement（差距表述，两种句式：有计算结果时用定量句；否则用“中方已核验证据为…，
                   另侧缺可核验证据”的证据差句）、evidenceStrength（strong=双侧官方或计算结果 /
                   partial=含第三方转述 / insufficient=单侧或无）、basisClaimIds。
                2. abstractLines：1—3 条摘要，每条标 kind（fact=直接引用 / calc=基于计算 / inference=显式
                   推断）并列 basisClaimIds。inference 句必须以“综合判断：”开头，不得包含任何数字、日期、倍数。
                3. overallAssessment：最多 3 条整体研判，全部以“综合判断：”开头，只做层级、结构、机制性
                   判断（如“差距的性质是运力约束而非卫星制造能力”），不得包含任何数字、日期、倍数，不得引入
                   新事实，每条 basisClaimIds 至少 2 条。
                4. summaryLines：1—3 条总结，规则同 abstractLines。

                数字只能复述计算结果或已核验结论中的数字，不得新算；没有某一国证据时该行 gapStatement
                写证据不足，不得给比较结论；未通过核验的子任务不要写成已完成分析；不计算总体竞争力得分或排名。

                JSON：{"gapMatrix":[{"aspect":"部署节奏与在轨规模","usStatus":"...","cnStatus":"...",
                "gapStatement":"...","evidenceStrength":"partial","basisClaimIds":["t1-..."]}],
                "abstractLines":[{"text":"...","kind":"calc","basisClaimIds":["t1-..."]}],
                "overallAssessment":[{"text":"综合判断：...","basisClaimIds":["t1-...","t2-..."]}],
                "summaryLines":[{"text":"...","kind":"fact","basisClaimIds":["t1-..."]}]}
                """ + "\n本轮分析方法 Skill：\n" + methodsSkill,
                "用户问题：" + question + "\n已核验结论：\n" + packet + "\n现状卡片：\n" + cardText
                        + "\n计算结果：\n" + calcText + "\n未通过核验的指标：" + excluded);
        try {
            Synthesis synthesis = mapper.readValue(json(result), Synthesis.class);
            return new Synthesis(synthesis.gapMatrix() == null ? List.of() : synthesis.gapMatrix(),
                    synthesis.abstractLines() == null ? List.of() : synthesis.abstractLines(),
                    synthesis.overallAssessment() == null ? List.of() : synthesis.overallAssessment(),
                    synthesis.summaryLines() == null ? List.of() : synthesis.summaryLines());
        } catch (Exception e) {
            throw new IllegalStateException("报告汇总 Agent 输出无效", e);
        }
    }

    /** Serializes a role output for run archives and SSE detail payloads. */
    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private void appendVerified(StringBuilder packet, List<ReportDocument.Claim> verified) {
        for (ReportDocument.Claim claim : verified)
            packet.append(claim.id()).append(" | ").append(claim.text()).append("\n");
    }

    private void appendCards(StringBuilder cardText, List<ReportDocument.StatusCard> cards) {
        for (ReportDocument.StatusCard card : cards)
            cardText.append(card.actorName()).append("(").append(card.country()).append(")：")
                    .append(card.fields().stream()
                            .map(f -> f.field() + "=" + f.value()
                                    + (f.asOfDate() == null ? "" : "@" + f.asOfDate()) + "[" + f.tier() + "]")
                            .reduce((a, b) -> a + "；" + b).orElse("无字段"))
                    .append("\n");
    }

    private void appendCalculations(StringBuilder calcText, List<ReportDocument.Calculation> calculations) {
        for (ReportDocument.Calculation calc : calculations)
            calcText.append(calc.id()).append(" | ").append(calc.formula()).append(" = ")
                    .append(calc.value()).append(calc.unit()).append("\n");
    }

    /**
     * 维度模块分析 Agent：对给定维度的全部指标逐项研判，基于已有材料尽最大努力给出详尽表达，
     * 材料不足时明确证据缺口；输出供写作 Agent 汇总取舍。
     */
    public DimensionAnalysis analyzeDimension(String question, String dimension, String frameworkSkill,
                                              List<ReportDocument.Claim> verified,
                                              List<ReportDocument.StatusCard> cards,
                                              List<ReportDocument.Calculation> calculations,
                                              Map<String, String> excluded, String methodsSkill) {
        StringBuilder packet = new StringBuilder();
        appendVerified(packet, verified);
        StringBuilder cardText = new StringBuilder();
        appendCards(cardText, cards);
        StringBuilder calcText = new StringBuilder();
        appendCalculations(calcText, calculations);
        String result = complete("""
                你是维度模块分析 Agent，负责维度「%s」。仅输出 JSON，不输出 Markdown。
                对本维度框架表中的每一个指标逐项输出研判，一个都不能少：

                - assessment：基于已核验结论、现状卡片与计算结果的详尽分析（200—500 字），尽最大努力
                  挖掘已有材料的信息量；证据充分时给结论并引用依据，材料不足时写清“证据缺口是什么、
                  需要什么材料才能补齐”，不得编造事实或数字。
                - evidenceState：verified=有已核验结论或卡片/计算支撑 / clue=仅有线索级材料 /
                  insufficient=本维度材料不足。
                - basisClaimIds：引用的依据编号，只能取自已核验结论的 id、卡片字段引用
                  （如 a1.inOrbit）或计算结果 id（如 calc-1）。
                - 指标状态为“条件可分析”或“可计算”的按框架边界写；政策、战略类指标必须依据官方
                  政策文件类来源的已核验结论，新闻转述只能作为线索描述。

                数字、日期只能复述材料中已有的；本输出只是研判底稿，最终是否采用由写作 Agent 决定。

                JSON：{"dimension":"%s","assessments":[{"indicator":"指标名","evidenceState":"verified",
                "assessment":"...","basisClaimIds":["t1-..."]}]}
                """.formatted(dimension, dimension)
                + "\n本轮竞争力框架 Skill：\n" + frameworkSkill
                + "\n本轮分析方法 Skill：\n" + methodsSkill,
                "用户问题：" + question + "\n已核验结论：\n" + packet + "\n现状卡片：\n" + cardText
                        + "\n计算结果：\n" + calcText + "\n未通过核验的子任务：" + excluded);
        try {
            DimensionAnalysis analysis = mapper.readValue(json(result), DimensionAnalysis.class);
            return new DimensionAnalysis(dimension,
                    analysis.assessments() == null ? List.of() : analysis.assessments());
        } catch (Exception e) {
            throw new IllegalStateException("维度模块分析 Agent 输出无效：" + dimension, e);
        }
    }

    /**
     * 写作 Agent：汇总四个维度的全指标研判底稿，决定最终采用哪些指标成文，
     * 并写摘要、总结、整体研判与差距矩阵；被弃用指标留档说明原因。
     */
    public WriterDraft writeReport(String question, List<DimensionAnalysis> modules,
                                   List<ReportDocument.Claim> verified,
                                   List<ReportDocument.StatusCard> cards,
                                   List<ReportDocument.Calculation> calculations,
                                   Map<String, String> excluded, String methodsSkill) {
        StringBuilder moduleText = new StringBuilder();
        for (DimensionAnalysis module : modules) {
            moduleText.append("## 维度：").append(module.dimension()).append("\n");
            for (IndicatorAssessment item : module.assessments())
                moduleText.append("- ").append(item.indicator()).append(" [").append(item.evidenceState())
                        .append("] ").append(item.assessment())
                        .append("（依据：").append(String.join(",", item.basisClaimIds() == null
                                ? List.of() : item.basisClaimIds())).append("）\n");
        }
        StringBuilder packet = new StringBuilder();
        appendVerified(packet, verified);
        StringBuilder cardText = new StringBuilder();
        appendCards(cardText, cards);
        StringBuilder calcText = new StringBuilder();
        appendCalculations(calcText, calculations);
        String result = complete("""
                你是写作 Agent。仅输出 JSON，不输出 Markdown。输入是四个维度模块的全指标研判底稿、
                已核验结论、现状卡片与计算结果。你的职责是择优成文：

                1. indicators：从 28 项指标的底稿中选出本报告最终采用的指标（宁缺毋滥，evidenceState
                   为 insufficient 且无实质内容的必须丢弃）。每项输出：dimension（四维之一）、indicator
                   （指标名）、reason（一句话采用理由）、text（成文正文，500—1200 字，充分展开已有材料，
                   结构：已核验事实→中美两侧对比（如有）→证据边界与缺口）、basisClaimIds（正文依据，
                   只能取自已核验结论 id、卡片字段引用（如 a1.inOrbit）或计算结果 id（如 calc-1），
                   至少 2 条）。
                2. droppedIndicators：被丢弃的指标名列表，每个附一句原因，格式“指标名：原因”。
                3. abstractLines：1—3 条摘要，每条标 kind（fact/calc/inference）并列 basisClaimIds；
                   inference 句以“综合判断：”开头且不含任何数字、日期、倍数。
                4. overallAssessment：最多 3 条整体研判，全部以“综合判断：”开头，只做层级、结构、
                   机制性判断，不得包含任何数字、日期、倍数，每条 basisClaimIds 至少 2 条。
                5. summaryLines：1—3 条总结，规则同 abstractLines。
                6. gapMatrix：差距矩阵（存在中美比较时必填，否则为空数组）——每行 aspect、usStatus
                   （美国侧已核验事实，含数字与基准日）、cnStatus（同上）、gapStatement（有计算结果时用
                   定量句；否则用证据差句）、evidenceStrength（strong/partial/insufficient）、basisClaimIds。

                正文中的数字、日期只能复述已核验结论、卡片或计算结果；不得新算；不计算总体竞争力得分
                或排名；未核验的内容必须标注为线索或转述。

                JSON：{"indicators":[{"dimension":"产业成熟度","indicator":"产能水平","reason":"...",
                "text":"...","basisClaimIds":["t1-..."]}],
                "droppedIndicators":["科研机构贡献：论文元数据未覆盖本轮主体"],
                "abstractLines":[{"text":"...","kind":"calc","basisClaimIds":["t1-..."]}],
                "overallAssessment":[{"text":"综合判断：...","basisClaimIds":["t1-...","t2-..."]}],
                "summaryLines":[{"text":"...","kind":"fact","basisClaimIds":["t1-..."]}],
                "gapMatrix":[{"aspect":"...","usStatus":"...","cnStatus":"...","gapStatement":"...",
                "evidenceStrength":"partial","basisClaimIds":["t1-..."]}]}
                """ + "\n本轮分析方法 Skill：\n" + methodsSkill,
                "用户问题：" + question + "\n维度研判底稿：\n" + moduleText
                        + "\n已核验结论：\n" + packet + "\n现状卡片：\n" + cardText
                        + "\n计算结果：\n" + calcText + "\n未通过核验的子任务：" + excluded);
        try {
            WriterDraft draft = mapper.readValue(json(result), WriterDraft.class);
            return new WriterDraft(
                    draft.indicators() == null ? List.of() : draft.indicators(),
                    draft.abstractLines() == null ? List.of() : draft.abstractLines(),
                    draft.overallAssessment() == null ? List.of() : draft.overallAssessment(),
                    draft.summaryLines() == null ? List.of() : draft.summaryLines(),
                    draft.gapMatrix() == null ? List.of() : draft.gapMatrix(),
                    draft.droppedIndicators() == null ? List.of() : draft.droppedIndicators());
        } catch (Exception e) {
            throw new IllegalStateException("写作 Agent 输出无效", e);
        }
    }

    private String complete(String system, String user) {
        String result = client.prompt().system(system).user(user).call().content();
        if (result == null || result.isBlank()) throw new IllegalStateException("Report model returned no content");
        return result;
    }

    private String json(String response) {
        int first = response.indexOf('{');
        int last = response.lastIndexOf('}');
        if (first < 0 || last <= first) throw new IllegalStateException("Model response is not JSON");
        return response.substring(first, last + 1);
    }
}
