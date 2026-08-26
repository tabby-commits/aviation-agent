package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.mapper.PaperTaxonomyMapper;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.entity.PaperTaxonomy;
import com.kama.jchatmind.model.response.MembershipImportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 论文分类归属导入集成测试（EASC 一级类目 → 新体系 code 映射）
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperMembershipImportTest {

    /** 覆盖：level2_assignment 正常映射、candidate_cluster 跳过、未匹配 L1、paper 表无此 doc_id */
    private static final String MEMBERS_CSV =
            "node_id,doc_id,title,first_level,second_level,membership_type\n"
            + "L2_1,WOS:TEST-MB1,Paper One,Space Computing Technologies,Some L2,level2_assignment\n"
            + "L2_2,WOS:TEST-MB1,Paper One,Constellation Design Technologies,Another L2,candidate_cluster\n"
            + "L2_3,WOS:TEST-MB2,Paper Two,Inter-Satellite Networking Technologies,Some L2,level2_assignment\n"
            + "L2_4,WOS:TEST-MB3,Paper Three,Unknown Level Name,Some L2,level2_assignment\n"
            + "L2_5,WOS:TEST-MB4,Paper Four,Space Computing Technologies,Some L2,level2_assignment\n";

    @Autowired
    private PaperFacadeService paperFacadeService;

    @Autowired
    private PaperMapper paperMapper;

    @Autowired
    private PaperTaxonomyMapper paperTaxonomyMapper;

    @BeforeEach
    public void setup() {
        // 只清理测试专属 docId（deleteBySource 会连带删除真实导入的归属数据，禁止使用）
        paperTaxonomyMapper.deleteByDocIdPrefix("WOS:TEST-MB");
        paperMapper.deleteByDocIdPrefix("WOS:TEST-MB");
        paperMapper.upsert(Paper.builder().docId("WOS:TEST-MB1").sourceDb("WOS")
                .title("Paper One").screeningStatus("pending").build());
        paperMapper.upsert(Paper.builder().docId("WOS:TEST-MB2").sourceDb("WOS")
                .title("Paper Two").screeningStatus("pending").build());
        paperMapper.upsert(Paper.builder().docId("WOS:TEST-MB3").sourceDb("WOS")
                .title("Paper Three").screeningStatus("pending").build());
    }

    @AfterEach
    public void cleanup() {
        paperTaxonomyMapper.deleteByDocIdPrefix("WOS:TEST-MB");
        paperMapper.deleteByDocIdPrefix("WOS:TEST-MB");
    }

    private List<PaperTaxonomy> imported(String docId) {
        return paperTaxonomyMapper.selectByDocId(docId).stream()
                .filter(m -> "easc_v19_level2".equals(m.getSource()))
                .toList();
    }

    @Test
    public void importMembershipsShouldMapAndSkipCorrectly() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "members.csv", "text/csv", MEMBERS_CSV.getBytes(StandardCharsets.UTF_8));

        MembershipImportResponse response = paperFacadeService.importTaxonomyMemberships(file);
        assertEquals(5, response.getTotal());
        assertEquals(2, response.getImported(), "MB1(level2)与MB2(level2)应导入");
        assertEquals(1, response.getCandidateSkipped(), "candidate_cluster 行应跳过");
        assertEquals(1, response.getUnmappedSkipped(), "未知一级类目应跳过");
        assertEquals(1, response.getNoPaperSkipped(), "paper 表缺失的 doc_id 应跳过");
        assertEquals(0, response.getFailed());

        // EASC L1 名称映射到新体系 code
        assertEquals(1, imported("WOS:TEST-MB1").size());
        assertEquals("space-computing", imported("WOS:TEST-MB1").get(0).getTaxonomyCode());
        assertEquals("inter-satellite-networking", imported("WOS:TEST-MB2").get(0).getTaxonomyCode());
        assertTrue(imported("WOS:TEST-MB3").isEmpty(), "未匹配 L1 不产生归属");
        assertTrue(imported("WOS:TEST-MB4").isEmpty(), "paper 缺失不产生归属");
        assertNull(null);
    }

    @Test
    public void importMembershipsShouldBeIdempotent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "members.csv", "text/csv", MEMBERS_CSV.getBytes(StandardCharsets.UTF_8));

        paperFacadeService.importTaxonomyMemberships(file);
        MembershipImportResponse second = paperFacadeService.importTaxonomyMemberships(file);

        // 第二次全部被唯一约束忽略：imported=0，且总数不变
        assertEquals(0, second.getImported());
        assertEquals(2, imported("WOS:TEST-MB1").size() + imported("WOS:TEST-MB2").size());
    }
}
