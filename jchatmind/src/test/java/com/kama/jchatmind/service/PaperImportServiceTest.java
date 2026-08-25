package com.kama.jchatmind.service;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.service.paper.CnkiRefWorksParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 论文元数据导入服务集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperImportServiceTest {

    private static final String WOS_CSV =
            "PT,AU,TI,SO,DT,PY,AB,TC,C1,UT,DI\n"
            + "C,\"A, B\",Title One,J1,Article,2020,Abs1,1,\"Inst, CN\",WOS:TEST-IMP1,\n"
            + "C,\"C, D\",Title Two,J2,Article,2021,Abs2,,\"Inst, US\",WOS:TEST-IMP2,10.1/x\n";

    private static final String CNKI_TXT = """
            RT Journal Article
            A1 作者甲;作者乙
            T1 中文测试论文一
            JF 测试期刊
            YR 2024

            """;

    /** CNKI 测试数据的完整 docId（清理时精确匹配，避免误删真实 CNKI 数据） */
    private static final String CNKI_TEST_DOC_ID =
            "CNKI:" + CnkiRefWorksParser.md5Hex("中文测试论文一|测试期刊|2024");

    @Autowired
    private PaperFacadeService paperFacadeService;

    @Autowired
    private PaperMapper paperMapper;

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
        paperMapper.deleteByDocIdPrefix(CNKI_TEST_DOC_ID);
    }

    @Test
    public void importWosShouldInsertThenUpdateIdempotently() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", WOS_CSV.getBytes(StandardCharsets.UTF_8));

        PaperImportResponse first = paperFacadeService.importMetadata(file, "WOS");
        assertEquals("WOS", first.getSourceDb());
        assertEquals(2, first.getTotal());
        assertEquals(2, first.getInserted());
        assertEquals(0, first.getUpdated());
        assertEquals(0, first.getFailed());

        // 第二次导入同内容：全部走更新，不产生重复
        PaperImportResponse second = paperFacadeService.importMetadata(file, "WOS");
        assertEquals(2, second.getTotal());
        assertEquals(0, second.getInserted());
        assertEquals(2, second.getUpdated());
        assertEquals(0, second.getFailed());
    }

    @Test
    public void importCnkiShouldConstructDocId() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cnki.txt", "text/plain", CNKI_TXT.getBytes(StandardCharsets.UTF_8));

        PaperImportResponse response = paperFacadeService.importMetadata(file, "CNKI");
        assertEquals(1, response.getInserted());
        assertNotNull(paperMapper.selectByDocId(CNKI_TEST_DOC_ID));
    }

    @Test
    public void sourceShouldBeCaseInsensitive() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", WOS_CSV.getBytes(StandardCharsets.UTF_8));
        PaperImportResponse response = paperFacadeService.importMetadata(file, "wos");
        assertEquals("WOS", response.getSourceDb());
    }

    @Test
    public void invalidSourceShouldThrowBizException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.csv", "text/csv", new byte[0]);
        assertThrows(BizException.class, () -> paperFacadeService.importMetadata(file, "INVALID"));
    }

    @Test
    public void emptyFileShouldReturnZeroTotals() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", "PT,AU,TI,UT\n".getBytes(StandardCharsets.UTF_8));
        PaperImportResponse response = paperFacadeService.importMetadata(file, "WOS");
        assertEquals(0, response.getTotal());
        assertEquals(0, response.getInserted());
    }
}
