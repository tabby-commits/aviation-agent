package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CNKI RefWorks 制式解析器单元测试
 */
public class CnkiRefWorksParserTest {

    private final CnkiRefWorksParser parser = new CnkiRefWorksParser();

    private static final String SAMPLE = """
            RT Journal Article
            SR 1
            A1 赵晶;王志浩;虞志刚
            AD 应急管理大学应急技术与管理学院;中国电子科技集团有限公司电子科学研究院;
            T1 面向空天地一体化网络的数字孪生系统架构
            JF 电讯技术
            YR 2026
            K1 空天地一体化网络;数字孪生
            AB 摘要文本。
            DO 10.20079/j.issn.1001-893x.260117001

            RT Journal Article
            SR 1
            A1 马越辰;黄美丽
            AD 北京空间飞行器总体设计部;
            T1 航天器轨道及星座设计与优化软件开发应用
            JF 航天器工程
            YR 2026
            AB 摘要二。

            """;

    @Test
    public void shouldParseRefWorksRecords() throws IOException {
        List<Paper> papers = parser.parse(new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, papers.size());

        Paper first = papers.get(0);
        assertEquals("CNKI", first.getSourceDb());
        assertEquals("面向空天地一体化网络的数字孪生系统架构", first.getTitle());
        assertEquals("[\"赵晶\",\"王志浩\",\"虞志刚\"]", first.getAuthors());
        assertEquals("赵晶", first.getFirstAuthor());
        // AD 尾部分号不应产生空机构
        assertEquals("[\"应急管理大学应急技术与管理学院\",\"中国电子科技集团有限公司电子科学研究院\"]",
                first.getAffiliations());
        assertEquals("应急管理大学应急技术与管理学院", first.getFirstAuthorAffiliation());
        assertEquals(2026, first.getPublishYear());
        assertEquals("电讯技术", first.getJournal());
        assertEquals("10.20079/j.issn.1001-893x.260117001", first.getDoi());
        assertEquals("空天地一体化网络;数字孪生", first.getKeywords());
        assertEquals("Journal Article", first.getDocType());
        assertEquals("pending", first.getScreeningStatus());
        // CNKI 无被引数据
        assertNull(first.getCitedCount());

        Paper second = papers.get(1);
        assertEquals("航天器轨道及星座设计与优化软件开发应用", second.getTitle());
        assertNull(second.getDoi());
        assertNull(second.getKeywords());
    }

    @Test
    public void docIdShouldBeDeterministicAndDerivedFromTitleJournalYear() throws IOException {
        List<Paper> papers = parser.parse(new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));

        String docId = papers.get(0).getDocId();
        assertTrue(docId.startsWith("CNKI:"), "docId 应以 CNKI: 前缀开头");
        assertEquals("CNKI:" + CnkiRefWorksParser.md5Hex("面向空天地一体化网络的数字孪生系统架构|电讯技术|2026"), docId);

        // 重复解析结果稳定（幂等导入的前提）
        List<Paper> again = parser.parse(new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(docId, again.get(0).getDocId());
    }

    @Test
    public void recordWithoutTitleShouldBeSkipped() throws IOException {
        String sample = "RT Journal Article\nSR 1\nJF 某期刊\nYR 2026\n\n";
        List<Paper> papers = parser.parse(new ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8)));
        assertTrue(papers.isEmpty(), "缺少 T1 标题的记录无法构造 docId，应跳过");
    }
}
