package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.service.BibliometricFacadeService;
import com.kama.jchatmind.service.TaxonomyFacadeService;
import com.kama.jchatmind.service.bibliometric.BibliometricResult;
import com.kama.jchatmind.model.response.TaxonomyPapersResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个 Agent 工具集成测试（基于真实入库数据，宽松断言）
 *
 * 前置要求：
 * 1. PostgreSQL（localhost:5432/jchatmind）已含第一、二步导入的真实数据（included=557 等）
 */
@SpringBootTest
public class AgentToolsIntegrationTest {

    @Autowired
    private BibliometricFacadeService bibliometricFacadeService;

    @Autowired
    private TaxonomyFacadeService taxonomyFacadeService;

    @Autowired
    private PaperSearchTool paperSearchTool;

    @Autowired
    private ParameterEvidenceTool parameterEvidenceTool;

    @Autowired
    private TaxonomyBrowseTool taxonomyBrowseTool;

    @Autowired
    private BibliometricTool bibliometricTool;

    @Test
    public void bibliometricOnRealDataShouldCoverCnAndUs() {
        BibliometricResult result = bibliometricFacadeService.analyze(1993, 2026, null);

        // 全集 = included 论文（557 篇，含 8 篇 2026 年）
        assertEquals(557, result.getTotalPapers());

        BibliometricResult.CountryStats cn = result.getByCountry().get("CN");
        BibliometricResult.CountryStats us = result.getByCountry().get("US");
        assertNotNull(cn, "真实库应含 CN");
        assertNotNull(us, "真实库应含 US");
        assertTrue(cn.getFullCount() > us.getFullCount(), "CN 参与论文数应多于 US（465 vs 92+合作）");
        assertTrue(cn.getFullCount() <= 557);
        assertTrue(cn.getFractionalCount() <= cn.getFullCount(), "分数计数不大于完全计数");
    }

    /**
     * 归属集（4488，EASC 语料）与有效论文集（557）来自小论文两条筛选管线，
     * 交集为部分覆盖（当前快照约 246 篇），分类限定缩小论文集且行为正确。
     */
    @Test
    public void bibliometricScopedByTaxonomyBehavesCorrectly() {
        BibliometricResult all = bibliometricFacadeService.analyze(1993, 2026, null);
        BibliometricResult scoped = bibliometricFacadeService.analyze(1993, 2026, "space-computing");

        assertTrue(scoped.getTotalPapers() > 0, "归属与有效论文存在部分交集");
        assertTrue(scoped.getTotalPapers() < all.getTotalPapers(), "分类限定应缩小论文集");
    }

    @Test
    public void taxonomyPapersShouldReturnCountryDistribution() {
        TaxonomyPapersResponse resp = taxonomyFacadeService.getTaxonomyPapers("space-computing", 10);
        assertEquals("空间计算与星载智能", resp.getNameCn());
        assertTrue(resp.getTotal() > 0);
        // 归属集论文大多未经国别筛选，分布至少含"未知"键
        assertTrue(!resp.getByCountry().isEmpty());
        assertTrue(resp.getPapers().size() <= 10);
    }

    @Test
    public void toolsShouldFormatReadableOutput() {
        String search = paperSearchTool.paperSearch("satellite", "CN", 2020, 2025, "included", "WOS", 1, 5);
        assertTrue(search.contains("共"), "PaperSearchTool 输出应含总数");

        String evidence = parameterEvidenceTool.queryEvidence(
                "latency and delay", null, null, null, 1, 5);
        assertTrue(evidence.contains("参数证据"), "ParameterEvidenceTool 输出应含标题");

        String tree = taxonomyBrowseTool.browse(null, null);
        assertTrue(tree.contains("space-computing"), "TaxonomyBrowseTool 应列出类别");

        String bib = bibliometricTool.analyze(2015, 2025, null);
        assertTrue(bib.contains("N_F"), "BibliometricTool 输出应含指标符号");
    }

    @Test
    public void optionalToolsShouldBeRegistered() {
        assertEquals(ToolType.OPTIONAL, paperSearchTool.getType());
        assertEquals(ToolType.OPTIONAL, parameterEvidenceTool.getType());
        assertEquals(ToolType.OPTIONAL, taxonomyBrowseTool.getType());
        assertEquals(ToolType.OPTIONAL, bibliometricTool.getType());
        List.of(paperSearchTool.getName(), parameterEvidenceTool.getName(),
                taxonomyBrowseTool.getName(), bibliometricTool.getName())
                .forEach(name -> assertTrue(!name.isBlank()));
    }
}
