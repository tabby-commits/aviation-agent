package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Paper upsert 幂等测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperMapperUpsertTest {

    private static final String TEST_DOC_ID = "WOS:TEST-0001";

    @Autowired
    private PaperMapper paperMapper;

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
    }

    private Paper buildPaper() {
        return Paper.builder()
                .docId(TEST_DOC_ID)
                .sourceDb("WOS")
                .title("Test Paper Title")
                .abstractText("Test abstract")
                .authors("[\"Author, A\",\"Author, B\"]")
                .affiliations("[\"Inst A, Beijing, China\"]")
                .firstAuthor("Author, A")
                .firstAuthorAffiliation("Inst A, Beijing, China")
                .publishYear(2024)
                .journal("Test Journal")
                .docType("Article")
                .citedCount(5)
                .doi("10.1000/test")
                .screeningStatus("pending")
                .build();
    }

    @Test
    public void upsertShouldInsertNewPaper() {
        int rows = paperMapper.upsert(buildPaper());
        assertEquals(1, rows);

        Paper saved = paperMapper.selectByDocId(TEST_DOC_ID);
        assertNotNull(saved);
        assertEquals("Test Paper Title", saved.getTitle());
        assertEquals("WOS", saved.getSourceDb());
        assertEquals(2024, saved.getPublishYear());
        // PG jsonb::text 规范化输出：逗号后带空格
        assertEquals("[\"Author, A\", \"Author, B\"]", saved.getAuthors());
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    public void upsertShouldUpdateExistingPaperWithoutDuplicate() {
        paperMapper.upsert(buildPaper());

        // 同 doc_id 变更字段后再次 upsert
        Paper updated = buildPaper();
        updated.setTitle("Updated Title");
        updated.setCitedCount(10);
        updated.setFirstAuthorCountry("CN");
        int rows = paperMapper.upsert(updated);
        assertEquals(1, rows);

        Paper saved = paperMapper.selectByDocId(TEST_DOC_ID);
        assertEquals("Updated Title", saved.getTitle());
        assertEquals(10, saved.getCitedCount());
        assertEquals("CN", saved.getFirstAuthorCountry());

        // 幂等：表中仍只有一条
        assertEquals(1, paperMapper.countByDocId(TEST_DOC_ID));
    }

    @Test
    public void upsertShouldNotOverwriteScreeningFieldsWithNull() {
        // 模拟筛选导入（第二步）已填充国别后，元数据重复导入不得覆盖
        Paper screened = buildPaper();
        screened.setFirstAuthorCountry("CN");
        screened.setCountryEvidence("作者机构地址含 China");
        screened.setFileName("IEEE_xxx.pdf");
        paperMapper.upsert(screened);

        // 元数据重复导入：这些字段为 null
        paperMapper.upsert(buildPaper());

        Paper saved = paperMapper.selectByDocId(TEST_DOC_ID);
        assertEquals("CN", saved.getFirstAuthorCountry());
        assertEquals("作者机构地址含 China", saved.getCountryEvidence());
        assertEquals("IEEE_xxx.pdf", saved.getFileName());
    }

    @Test
    public void selectByDocIdShouldReturnNullWhenAbsent() {
        assertNull(paperMapper.selectByDocId("WOS:TEST-NOT-EXIST"));
    }
}
