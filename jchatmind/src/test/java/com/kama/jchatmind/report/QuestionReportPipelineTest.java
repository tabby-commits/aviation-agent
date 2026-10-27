package com.kama.jchatmind.report;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.service.SkillService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static com.kama.jchatmind.report.ReportDocument.*;

class QuestionReportPipelineTest {

    private QuestionReportPipeline pipeline(ResearchRoleModel roles, WebEvidenceCollector collector,
                                            SkillService skills, ReportStore store) {
        return new QuestionReportPipeline(roles, collector, skills, store,
                new CitationValidator(), mock(SseService.class), "", 1);
    }

    private void stubSkills(SkillService skills) {
        for (String name : List.of("competitiveness-framework", "data-resource-rules", "ci-analysis-methods")) {
            when(skills.getSkillByName(name)).thenReturn(Optional.of(SkillDefinition.builder()
                    .name(name).rawContent("version: test").build()));
        }
        when(skills.invokeSkill(anyString(), anyString())).thenReturn("skill invoked");
    }

    private WebEvidenceCollector.Evidence evidence() {
        return new WebEvidenceCollector.Evidence("source-1", "Institution",
                "https://example.org/original", "2025-01-01", "2026-10-26", "sha256", "raw.html",
                "The institution reported 27 satellites in orbit after its launch.");
    }

    /** 空模块 + 空写作稿：流水线应走回退路径（现状任务结论直接成文）。 */
    private void stubModuleAndWriter(ResearchRoleModel roles) {
        when(roles.analyzeDimension(anyString(), anyString(), anyString(), anyList(), anyList(),
                anyList(), anyMap(), anyString()))
                .thenAnswer(invocation -> new ResearchRoleModel.DimensionAnalysis(
                        invocation.getArgument(1), List.of()));
        when(roles.writeReport(anyString(), anyList(), anyList(), anyList(), anyList(), anyMap(), anyString()))
                .thenReturn(new ResearchRoleModel.WriterDraft(List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()));
    }

    @Test
    void repeatedQuestionsPlanAndGenerateIndependentReports() {
        ResearchRoleModel roles = mock(ResearchRoleModel.class);
        WebEvidenceCollector collector = mock(WebEvidenceCollector.class);
        SkillService skills = mock(SkillService.class);
        ReportStore store = mock(ReportStore.class);
        QuestionReportPipeline pipeline = pipeline(roles, collector, skills, store);
        stubSkills(skills);
        when(store.start(anyString(), eq("research"), anyString(), anyMap())).thenReturn("run-a", "run-b");
        when(store.nextVersion("session", "research")).thenReturn(1, 2);
        ResearchRoleModel.Actor actor = new ResearchRoleModel.Actor("a1", "主体一", "CN", List.of("主体一"));
        ResearchRoleModel.Task taskA = new ResearchRoleModel.Task("a", "actor-status", "a1", "产业成熟度",
                "产能水平：主体一", "分析主体一进度", List.of("query-a"));
        when(roles.plan(eq("分析低轨建设"), anyString())).thenReturn(
                new ResearchRoleModel.Plan("低轨建设分析", "leo", List.of(actor), List.of(), List.of(taskA)));
        when(collector.collect(anyString(), anyList(), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(evidence()), List.of(), List.of(), List.of()));
        when(roles.analyze(anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(null, List.of(
                        new ResearchRoleModel.DraftClaim("机构披露已有 27 颗卫星在轨。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-1",
                                "The institution reported 27 satellites in orbit after its launch.")))),
                        List.of(), List.of()));
        stubModuleAndWriter(roles);

        ReportDocument first = pipeline.run("session", "分析低轨建设", text -> {});
        ReportDocument second = pipeline.run("session", "分析低轨建设", text -> {});

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals("低轨建设分析", second.title());
        assertEquals(1, second.dimensions().size());
        verify(roles, times(2)).plan("分析低轨建设", "skill invoked");
        verify(roles, times(2)).analyze(anyString(), any(), anyList(),
                eq("skill invoked"), eq("skill invoked"));
        verify(collector, times(2)).collect(anyString(), anyList(), anyInt(), anyInt(),
                any(Duration.class), any());
        verify(store, times(2)).save(any(ReportDocument.class));
    }

    @Test
    void unsupportedNumberNeverBecomesAReport() {
        ResearchRoleModel roles = mock(ResearchRoleModel.class);
        WebEvidenceCollector collector = mock(WebEvidenceCollector.class);
        SkillService skills = mock(SkillService.class);
        ReportStore store = mock(ReportStore.class);
        QuestionReportPipeline pipeline = pipeline(roles, collector, skills, store);
        stubSkills(skills);
        when(store.start(anyString(), anyString(), anyString(), anyMap())).thenReturn("run-c");
        ResearchRoleModel.Actor actor = new ResearchRoleModel.Actor("a1", "主体一", "CN", List.of("主体一"));
        ResearchRoleModel.Task task = new ResearchRoleModel.Task("a", "actor-status", "a1", "产业成熟度",
                "产能水平：主体一", "主体一规模多少", List.of("query"));
        when(roles.plan(anyString(), anyString())).thenReturn(
                new ResearchRoleModel.Plan("建设报告", "leo", List.of(actor), List.of(), List.of(task)));
        WebEvidenceCollector.Evidence evidence = new WebEvidenceCollector.Evidence("source-1", "Institution",
                "https://example.org/original", "", "2026-10-26", "sha256", "raw.html",
                "The institution reported 27 satellites in orbit after its launch.");
        when(collector.collect(anyString(), anyList(), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(evidence), List.of(), List.of(), List.of()));
        when(roles.analyze(anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(null, List.of(
                        new ResearchRoleModel.DraftClaim("机构披露已有 999 颗卫星在轨。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-1",
                                "The institution reported 27 satellites in orbit after its launch.")))),
                        List.of(), List.of()));

        assertThrows(IllegalStateException.class, () -> pipeline.run("session", "建设规模", text -> {}));
        verify(store, never()).save(any());
        verify(store).finish("run-c", "failed", "本轮未取得通过原文核对的证据，未生成报告");
    }

    @Test
    void missingEvidenceTriggersAtMostOneRecollection() {
        ResearchRoleModel roles = mock(ResearchRoleModel.class);
        WebEvidenceCollector collector = mock(WebEvidenceCollector.class);
        SkillService skills = mock(SkillService.class);
        ReportStore store = mock(ReportStore.class);
        QuestionReportPipeline pipeline = pipeline(roles, collector, skills, store);
        stubSkills(skills);
        when(store.start(anyString(), anyString(), anyString(), anyMap())).thenReturn("run-d");
        when(store.nextVersion(anyString(), anyString())).thenReturn(1);
        ResearchRoleModel.Actor actor = new ResearchRoleModel.Actor("a1", "主体一", "CN", List.of("主体一"));
        ResearchRoleModel.Task task = new ResearchRoleModel.Task("a", "actor-status", "a1", "产业成熟度",
                "产能水平：主体一", "主体一在轨规模", List.of("query"));
        when(roles.plan(anyString(), anyString())).thenReturn(
                new ResearchRoleModel.Plan("建设报告", "leo", List.of(actor), List.of(), List.of(task)));
        when(collector.collect(anyString(), anyList(), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(evidence()), List.of(), List.of(), List.of()));
        // Both rounds report a gap but keep a verifiable claim, so exactly two analyze calls must occur.
        when(roles.analyze(anyString(), any(), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(null, List.of(
                        new ResearchRoleModel.DraftClaim("机构披露已有 27 颗卫星在轨。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-1",
                                "The institution reported 27 satellites in orbit after its launch.")))),
                        List.of("缺少在轨基准日"), List.of()));
        stubModuleAndWriter(roles);

        ReportDocument report = pipeline.run("session", "主体一在轨规模", text -> {});

        assertEquals(1, report.dimensions().size());
        verify(roles, times(2)).analyze(anyString(), any(), anyList(),
                eq("skill invoked"), eq("skill invoked"));
        verify(collector, times(2)).collect(anyString(), anyList(), anyInt(), anyInt(),
                any(Duration.class), any());
    }

    @Test
    void writerAgentSelectsIndicatorsAndNumbersStayTraceable() {
        ResearchRoleModel roles = mock(ResearchRoleModel.class);
        WebEvidenceCollector collector = mock(WebEvidenceCollector.class);
        SkillService skills = mock(SkillService.class);
        ReportStore store = mock(ReportStore.class);
        QuestionReportPipeline pipeline = pipeline(roles, collector, skills, store);
        stubSkills(skills);
        when(store.start(anyString(), anyString(), anyString(), anyMap())).thenReturn("run-e");
        when(store.nextVersion(anyString(), anyString())).thenReturn(1);
        ResearchRoleModel.Actor us = new ResearchRoleModel.Actor("a1", "US 星座", "US", List.of("US 星座"));
        ResearchRoleModel.Actor cn = new ResearchRoleModel.Actor("a2", "CN 星座", "CN", List.of("CN 星座"));
        ResearchRoleModel.Task taskUs = new ResearchRoleModel.Task("t1", "actor-status", "a1", "产业成熟度",
                "产能水平：US 星座", "US 星座在轨规模", List.of("query-us"));
        ResearchRoleModel.Task taskCn = new ResearchRoleModel.Task("t2", "actor-status", "a2", "产业成熟度",
                "产能水平：CN 星座", "CN 星座在轨规模", List.of("query-cn"));
        when(roles.plan(anyString(), anyString())).thenReturn(new ResearchRoleModel.Plan("规模比较", "leo",
                List.of(us, cn), List.of("在轨规模"), List.of(taskUs, taskCn)));
        WebEvidenceCollector.Evidence usEvidence = new WebEvidenceCollector.Evidence("source-us", "US tracker",
                "https://example.org/us", "2026-01-01", "2026-10-26", "sha-us", "us.html",
                "The tracker counted 200 satellites in orbit on 2026-01-01.");
        WebEvidenceCollector.Evidence cnEvidence = new WebEvidenceCollector.Evidence("source-cn", "CN tracker",
                "https://example.org/cn", "2026-01-01", "2026-10-26", "sha-cn", "cn.html",
                "The tracker counted 50 satellites in orbit on 2026-01-01.");
        when(collector.collect(anyString(), eq(List.of("query-us")), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(usEvidence), List.of(), List.of(), List.of()));
        when(collector.collect(anyString(), eq(List.of("query-cn")), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(cnEvidence), List.of(), List.of(), List.of()));
        when(roles.analyze(eq("比较中美在轨规模"), eq(taskUs), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(
                        new ResearchRoleModel.StatusCard("a1", List.of(new ResearchRoleModel.CardField(
                                "inOrbit", "200", "2026-01-01", "third-party", "source-us",
                                "The tracker counted 200 satellites in orbit on 2026-01-01."))),
                        List.of(new ResearchRoleModel.DraftClaim("US 侧在轨 200 颗。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-us",
                                "The tracker counted 200 satellites in orbit on 2026-01-01.")))),
                        List.of(), List.of(new ResearchRoleModel.CompareRequest("在轨卫星数", "a1", "a2", "颗", "ratio"))));
        when(roles.analyze(eq("比较中美在轨规模"), eq(taskCn), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(
                        new ResearchRoleModel.StatusCard("a2", List.of(new ResearchRoleModel.CardField(
                                "inOrbit", "50", "2026-01-01", "third-party", "source-cn",
                                "The tracker counted 50 satellites in orbit on 2026-01-01."))),
                        List.of(new ResearchRoleModel.DraftClaim("CN 侧在轨 50 颗。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-cn",
                                "The tracker counted 50 satellites in orbit on 2026-01-01.")))),
                        List.of(), List.of()));
        when(roles.compare(anyList(), anyList())).thenReturn(new ResearchRoleModel.ComparePlan(List.of(
                new ResearchRoleModel.CalcPlanItem("calc-1", "ratio", "在轨卫星数", "颗",
                        "a1.inOrbit", "a2.inOrbit", "两侧同日基准"))));
        when(roles.analyzeDimension(anyString(), anyString(), anyString(), anyList(), anyList(),
                anyList(), anyMap(), anyString()))
                .thenAnswer(invocation -> new ResearchRoleModel.DimensionAnalysis(
                        invocation.getArgument(1),
                        List.of(new ResearchRoleModel.IndicatorAssessment("产能水平", "verified",
                                "两侧在轨规模已核验，比例约 4 倍。", List.of("a1.inOrbit", "a2.inOrbit")))));
        // 写作 Agent 采用“产能水平”：正文数字 200/50 均可回溯到卡片字段；另带一条无法回溯数字的候选应被拒
        when(roles.writeReport(anyString(), anyList(), anyList(), anyList(), anyList(), anyMap(), anyString()))
                .thenReturn(new ResearchRoleModel.WriterDraft(
                        List.of(new ResearchRoleModel.WriterIndicator("产业成熟度", "产能水平",
                                "双侧已核验同日存量", "按已核验同日存量，US 侧在轨 200 颗、CN 侧在轨 50 颗。",
                                List.of("a1.inOrbit", "a2.inOrbit")),
                                new ResearchRoleModel.WriterIndicator("科技创新能力", "核心论文数量",
                                        "数字不可回溯", "论文总量已达 9999 篇。", List.of("a1.inOrbit"))),
                        List.of(), List.of(), List.of(), List.of(),
                        List.of("科研机构贡献：论文元数据未覆盖本轮主体")));

        ReportDocument report = pipeline.run("session", "比较中美在轨规模", text -> {});

        assertEquals(1, report.calculations().size());
        assertEquals("4", report.calculations().get(0).value());
        assertEquals("倍", report.calculations().get(0).unit());
        assertEquals(2, report.statusCards().size());
        assertTrue(report.excludedIndicators().keySet().stream()
                .anyMatch(key -> key.startsWith("未采用：科研机构贡献")),
                "写作 Agent 弃用的指标应留档");
        List<Indicator> maturity = report.dimensions().stream()
                .filter(d -> d.name().equals("产业成熟度")).findFirst().orElseThrow().indicators();
        assertTrue(maturity.stream().anyMatch(i -> i.name().equals("产能水平")
                        && i.paragraphs().get(0).text().contains("200")),
                "被采用的指标正文应进入报告");
        assertTrue(report.dimensions().stream().flatMap(d -> d.indicators().stream())
                        .noneMatch(i -> i.name().equals("核心论文数量")),
                "含不可回溯数字的指标应被拒收");
    }

    @Test
    void duplicateCardFieldNamesKeepOnlyTheFirstVerifiedValue() {
        ResearchRoleModel roles = mock(ResearchRoleModel.class);
        WebEvidenceCollector collector = mock(WebEvidenceCollector.class);
        SkillService skills = mock(SkillService.class);
        ReportStore store = mock(ReportStore.class);
        QuestionReportPipeline pipeline = pipeline(roles, collector, skills, store);
        stubSkills(skills);
        when(store.start(anyString(), anyString(), anyString(), anyMap())).thenReturn("run-f");
        when(store.nextVersion(anyString(), anyString())).thenReturn(1);
        ResearchRoleModel.Actor us = new ResearchRoleModel.Actor("a1", "US 星座", "US", List.of("US 星座"));
        ResearchRoleModel.Actor cn = new ResearchRoleModel.Actor("a2", "CN 星座", "CN", List.of("CN 星座"));
        ResearchRoleModel.Task taskUs = new ResearchRoleModel.Task("t1", "actor-status", "a1", "产业成熟度",
                "产能水平：US 星座", "US 星座在轨规模", List.of("query-us"));
        ResearchRoleModel.Task taskCn = new ResearchRoleModel.Task("t2", "actor-status", "a2", "产业成熟度",
                "产能水平：CN 星座", "CN 星座在轨规模", List.of("query-cn"));
        when(roles.plan(anyString(), anyString())).thenReturn(new ResearchRoleModel.Plan("规模比较", "leo",
                List.of(us, cn), List.of(), List.of(taskUs, taskCn)));
        WebEvidenceCollector.Evidence usEvidence = new WebEvidenceCollector.Evidence("source-us", "US tracker",
                "https://example.org/us", "2026-01-01", "2026-10-26", "sha-us", "us.html",
                "The tracker counted 200 satellites in orbit on 2026-01-01.");
        WebEvidenceCollector.Evidence cnEvidence = new WebEvidenceCollector.Evidence("source-cn", "CN tracker",
                "https://example.org/cn", "2026-01-01", "2026-10-26", "sha-cn", "cn.html",
                "The tracker counted 50 satellites in orbit on 2026-01-01.");
        when(collector.collect(anyString(), eq(List.of("query-us")), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(usEvidence), List.of(), List.of(), List.of()));
        when(collector.collect(anyString(), eq(List.of("query-cn")), anyInt(), anyInt(), any(Duration.class), any()))
                .thenReturn(new WebEvidenceCollector.Retrieval(List.of(cnEvidence), List.of(), List.of(), List.of()));
        // The draft repeats inOrbit with a second source and date; both occurrences are
        // individually verifiable, so deduplication must happen before the final validator.
        when(roles.analyze(eq("比较中美在轨规模"), eq(taskUs), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(
                        new ResearchRoleModel.StatusCard("a1", List.of(
                                new ResearchRoleModel.CardField("inOrbit", "200", "2026-01-01",
                                        "third-party", "source-us",
                                        "The tracker counted 200 satellites in orbit on 2026-01-01."),
                                new ResearchRoleModel.CardField("inOrbit", "186", "2026-06-01",
                                        "official", "source-us",
                                        "The tracker counted 200 satellites in orbit on 2026-01-01."))),
                        List.of(new ResearchRoleModel.DraftClaim("US 侧在轨 200 颗。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-us",
                                "The tracker counted 200 satellites in orbit on 2026-01-01.")))),
                        List.of(), List.of()));
        when(roles.analyze(eq("比较中美在轨规模"), eq(taskCn), anyList(), anyString(), anyString()))
                .thenReturn(new ResearchRoleModel.TaskDraft(
                        new ResearchRoleModel.StatusCard("a2", List.of(new ResearchRoleModel.CardField(
                                "inOrbit", "50", "2026-01-01", "third-party", "source-cn",
                                "The tracker counted 50 satellites in orbit on 2026-01-01."))),
                        List.of(new ResearchRoleModel.DraftClaim("CN 侧在轨 50 颗。",
                                "fact", List.of(new ResearchRoleModel.Quote("source-cn",
                                "The tracker counted 50 satellites in orbit on 2026-01-01.")))),
                        List.of(), List.of()));
        stubModuleAndWriter(roles);

        ReportDocument report = pipeline.run("session", "比较中美在轨规模", text -> {});

        assertEquals(2, report.statusCards().size());
        List<ReportDocument.CardField> a1Fields = report.statusCards().stream()
                .filter(card -> card.actorId().equals("a1")).findFirst().orElseThrow().fields();
        assertEquals(1, a1Fields.size());
        assertEquals("inOrbit", a1Fields.get(0).field());
        assertEquals("200", a1Fields.get(0).value());
        verify(store).save(any(ReportDocument.class));
    }

    @Test
    void degradedReportPassesValidationWhenTheFullReportIsRejected() {
        ReportDocument.Source source = new ReportDocument.Source("s1", "external", "Tracker",
                "https://example.org/us", "https://example.org/us", "excerpt", "sha", "2026-10-26",
                "机器核验");
        // Duplicate inOrbit in one card is exactly the rejection that used to discard the run.
        ReportDocument.StatusCard card = new ReportDocument.StatusCard("a1", "US 星座", "US", List.of(
                new ReportDocument.CardField("inOrbit", "200", "2026-01-01", "third-party", "s1", "quote"),
                new ReportDocument.CardField("inOrbit", "186", "2026-06-01", "official", "s1", "quote")));
        Calculation calc = new Calculation("c1", "field-ratio", "在轨卫星数：a1.inOrbit ÷ a2.inOrbit",
                List.of("a1.inOrbit", "a2.inOrbit"), "4", "倍");
        Claim plain = new Claim("k1", "US 侧在轨 200 颗。", List.of("s1"), null, null, null);
        Claim calcClaim = new Claim("k2", "US 侧在轨为 CN 侧的 4 倍。", List.of("s1"), "c1", "4", "倍");
        ReportDocument full = new ReportDocument("id", "run", "session", "research", "规模比较",
                "比较中美在轨规模", "snap", 1, Instant.now(), List.of(source), List.of(calc),
                List.of(plain),
                List.of(new Dimension("产业成熟度", List.of(new Indicator("23", "在轨规模",
                        List.of(plain, calcClaim), null)))),
                List.of(), Map.of(), Map.of(), List.of(card),
                List.of(new ReportDocument.GapRow("在轨规模", "200", "50", "差距 4 倍",
                        "medium", List.of("k1"))), List.of());
        CitationValidator validator = new CitationValidator();
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> validator.validate(full));
        assertTrue(rejected.getMessage().contains("Duplicate card field: a1.inOrbit"));

        QuestionReportPipeline pipeline = pipeline(mock(ResearchRoleModel.class),
                mock(WebEvidenceCollector.class), mock(SkillService.class), mock(ReportStore.class));
        ReportDocument degraded = pipeline.degradedReport(full,
                new LinkedHashMap<>(Map.of("完整性降级", "终检未通过，已移除现状卡片与对比计算")));

        assertDoesNotThrow(() -> validator.validate(degraded));
        assertTrue(degraded.statusCards().isEmpty());
        assertTrue(degraded.calculations().isEmpty());
        assertTrue(degraded.excludedIndicators().containsKey("完整性降级"));
        List<Claim> paragraphs = degraded.dimensions().get(0).indicators().get(0).paragraphs();
        assertEquals(1, paragraphs.size());
        assertEquals("k1", paragraphs.get(0).id());
        assertEquals(1, degraded.gapMatrix().size());
    }
}
