package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.response.PaperScreeningImportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 筛选结论导入服务集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperScreeningImportTest {

    /** 覆盖：included+CN、excluded+US（含 reason）、不存在 doc_id 三种情形 */
    private static final String SCREEN_CSV =
            "batch_rank,sample_rank,sample_seed,doc_id,file_name,first_author_country,"
            + "country_evidence,country_confidence,country_status,include,evidence,reason\n"
            + "1,1,1,WOS:TEST-SCR1,IEEE_x1.pdf,CN,作者机构地址含 China,1.0,ok,True,题名含星间链路,\n"
            + "2,2,2,WOS:TEST-SCR2,IEEE_x2.pdf,US,第一机构地址为 USA,1.0,ok,False,,主题不相关：纯地面网络\n"
            + "3,3,3,WOS:TEST-SCR-ABSENT,IEEE_x3.pdf,JP,机构地址含 Japan,1.0,ok,True,,\n";

    @Autowired
    private PaperFacadeService paperFacadeService;

    @Autowired
    private PaperMapper paperMapper;

    @BeforeEach
    public void setup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-SCR");
        paperMapper.upsert(Paper.builder()
                .docId("WOS:TEST-SCR1").sourceDb("WOS")
                .title("Paper One").screeningStatus("pending").build());
        paperMapper.upsert(Paper.builder()
                .docId("WOS:TEST-SCR2").sourceDb("WOS")
                .title("Paper Two").screeningStatus("pending").build());
    }

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-SCR");
    }

    @Test
    public void importScreeningShouldUpdateExistingPapers() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "screen.csv", "text/csv", SCREEN_CSV.getBytes(StandardCharsets.UTF_8));

        PaperScreeningImportResponse response = paperFacadeService.importScreening(file);
        assertEquals(3, response.getTotal());
        assertEquals(2, response.getUpdated());
        assertEquals(1, response.getSkipped());
        assertEquals(0, response.getFailed());

        Paper included = paperMapper.selectByDocId("WOS:TEST-SCR1");
        assertEquals("included", included.getScreeningStatus());
        assertEquals("CN", included.getFirstAuthorCountry());
        assertEquals("作者机构地址含 China", included.getCountryEvidence());
        assertEquals("1.0", included.getCountryConfidence());
        assertEquals("IEEE_x1.pdf", included.getFileName());
        assertNull(included.getExcludeReason());

        Paper excluded = paperMapper.selectByDocId("WOS:TEST-SCR2");
        assertEquals("excluded", excluded.getScreeningStatus());
        assertEquals("US", excluded.getFirstAuthorCountry());
        assertEquals("主题不相关：纯地面网络", excluded.getExcludeReason());

        // 不存在的 doc_id 不产生新记录
        assertNull(paperMapper.selectByDocId("WOS:TEST-SCR-ABSENT"));
    }

    @Test
    public void importScreeningShouldBeIdempotent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "screen.csv", "text/csv", SCREEN_CSV.getBytes(StandardCharsets.UTF_8));

        paperFacadeService.importScreening(file);
        PaperScreeningImportResponse second = paperFacadeService.importScreening(file);

        // 筛选导入是覆盖式 UPDATE，重复导入结果一致
        assertEquals(2, second.getUpdated());
        assertEquals(1, second.getSkipped());
        assertEquals("included", paperMapper.selectByDocId("WOS:TEST-SCR1").getScreeningStatus());
    }

    @Test
    public void importScreeningShouldNotOverwriteFieldsWithNull() {
        // 预置 file_name（模拟其他来源已填充），导入行 file_name 为空时不得清空
        paperMapper.upsert(Paper.builder()
                .docId("WOS:TEST-SCR1").sourceDb("WOS").title("Paper One")
                .screeningStatus("pending").fileName("EXISTING.pdf").build());

        String csv = "doc_id,file_name,first_author_country,country_evidence,country_confidence,include,reason\n"
                + "WOS:TEST-SCR1,,CN,新证据,1.0,True,\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "screen.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        paperFacadeService.importScreening(file);

        Paper saved = paperMapper.selectByDocId("WOS:TEST-SCR1");
        assertEquals("EXISTING.pdf", saved.getFileName());
        assertEquals("新证据", saved.getCountryEvidence());
    }
}
