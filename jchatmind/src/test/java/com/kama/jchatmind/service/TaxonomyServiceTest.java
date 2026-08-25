package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.PaperTaxonomyMapper;
import com.kama.jchatmind.model.entity.PaperTaxonomy;
import com.kama.jchatmind.model.response.TaxonomyNodeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类体系内置与查询集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class TaxonomyServiceTest {

    @Autowired
    private TaxonomyFacadeService taxonomyFacadeService;

    @Autowired
    private PaperTaxonomyMapper paperTaxonomyMapper;

    @AfterEach
    public void cleanup() {
        paperTaxonomyMapper.deleteBySource("TEST");
    }

    @Test
    public void taxonomySeedShouldContainSixCategories() {
        List<TaxonomyNodeResponse> nodes = taxonomyFacadeService.getTaxonomyTree();
        assertEquals(6, nodes.size(), "分类体系应为 1 层 6 类");
        assertTrue(nodes.stream().allMatch(n -> n.getLevel() == 1));

        List<String> codes = nodes.stream().map(TaxonomyNodeResponse::getCode).toList();
        assertTrue(codes.containsAll(List.of(
                "constellation-design", "optical-isl", "inter-satellite-networking",
                "space-computing", "sat-ground-integration", "interference-mitigation")));

        // name_en 与 EASC 一级类目 1:1（归属导入映射的前提）
        assertTrue(nodes.stream().anyMatch(n ->
                "Space Computing Technologies".equals(n.getNameEn()) && "空间计算与星载智能".equals(n.getNameCn())));
    }

    @Test
    public void paperCountShouldAggregateFromPaperTaxonomy() {
        paperTaxonomyMapper.insertIgnore(PaperTaxonomy.builder()
                .docId("WOS:TEST-TX1").taxonomyCode("space-computing")
                .membershipType("assigned").source("TEST").build());
        paperTaxonomyMapper.insertIgnore(PaperTaxonomy.builder()
                .docId("WOS:TEST-TX2").taxonomyCode("space-computing")
                .membershipType("assigned").source("TEST").build());

        List<TaxonomyNodeResponse> nodes = taxonomyFacadeService.getTaxonomyTree();
        TaxonomyNodeResponse computing = nodes.stream()
                .filter(n -> "space-computing".equals(n.getCode()))
                .findFirst().orElseThrow();
        assertTrue(computing.getPaperCount() >= 2, "论文数应实时聚合（含测试插入 2 条）");
    }

    @Test
    public void insertIgnoreShouldBeIdempotent() {
        PaperTaxonomy membership = PaperTaxonomy.builder()
                .docId("WOS:TEST-TX3").taxonomyCode("optical-isl")
                .membershipType("assigned").source("TEST").build();
        assertEquals(1, paperTaxonomyMapper.insertIgnore(membership));
        // 相同 (doc_id, code, type) 重复插入被唯一约束忽略
        assertEquals(0, paperTaxonomyMapper.insertIgnore(membership));
        assertEquals(1, paperTaxonomyMapper.countByCode("optical-isl") >= 1 ? 1 : 0);
    }
}
