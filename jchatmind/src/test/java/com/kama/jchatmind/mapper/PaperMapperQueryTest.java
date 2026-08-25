package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper 分页条件查询集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperMapperQueryTest {

    @Autowired
    private PaperMapper paperMapper;

    @BeforeEach
    public void setup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
        for (int i = 1; i <= 5; i++) {
            paperMapper.upsert(Paper.builder()
                    .docId("WOS:TEST-Q" + i)
                    .sourceDb(i <= 3 ? "WOS" : "CNKI")
                    .title("LEO Satellite Paper " + i)
                    .firstAuthorCountry(i == 1 ? "CN" : "US")
                    .publishYear(2020 + i)
                    .screeningStatus(i == 5 ? "excluded" : "included")
                    .build());
        }
    }

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
    }

    @Test
    public void shouldFilterBySourceDb() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setSourceDb("CNKI");
        List<Paper> papers = paperMapper.selectByCondition(query);
        assertTrue(papers.stream().allMatch(p -> "CNKI".equals(p.getSourceDb())));
        assertEquals(2, papers.size());
        assertEquals(2, paperMapper.countByCondition(query));
    }

    @Test
    public void shouldFilterByYearRangeAndCountry() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setYearFrom(2022);
        query.setYearTo(2024);
        query.setCountry("US");
        List<Paper> papers = paperMapper.selectByCondition(query);
        // 2022~2024 且 US：Q2(2022) Q3(2023) Q4(2024)
        assertEquals(3, papers.size());
        assertTrue(papers.stream().allMatch(p -> "US".equals(p.getFirstAuthorCountry())));
    }

    @Test
    public void shouldFilterByKeywordInTitleOrAbstract() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setKeyword("LEO Satellite Paper 3");
        assertEquals(1, paperMapper.selectByCondition(query).size());
    }

    @Test
    public void shouldFilterByScreeningStatus() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setScreeningStatus("excluded");
        List<Paper> papers = paperMapper.selectByCondition(query);
        assertEquals(1, papers.size());
        assertEquals("WOS:TEST-Q5", papers.get(0).getDocId());
    }

    @Test
    public void shouldPaginate() {
        PaperQueryRequest page1 = new PaperQueryRequest();
        page1.setPage(1);
        page1.setPageSize(2);
        assertEquals(2, paperMapper.selectByCondition(page1).size());

        PaperQueryRequest page3 = new PaperQueryRequest();
        page3.setPage(3);
        page3.setPageSize(2);
        // 共 5 条，第 3 页只余 1 条
        assertEquals(1, paperMapper.selectByCondition(page3).size());
    }

    @Test
    public void emptyQueryShouldRespectDefaultPaging() {
        PaperQueryRequest query = new PaperQueryRequest(); // page=1, pageSize=20
        long total = paperMapper.countByCondition(query);
        // 默认分页生效：返回条数受 pageSize=20 约束，且测试数据在默认页内全部可数
        assertTrue(paperMapper.selectByCondition(query).size() <= 20);
        assertTrue(total >= 5, "至少应包含本测试插入的 5 条数据");
    }
}
