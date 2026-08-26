package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.TaxonomyMapper;
import com.kama.jchatmind.model.entity.TaxonomyNode;
import com.kama.jchatmind.model.response.TaxonomyNodeResponse;
import com.kama.jchatmind.model.response.TaxonomyPapersResponse;
import com.kama.jchatmind.service.TaxonomyFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类体系门面服务：节点查询、论文计数聚合与节点论文列表
 */
@Service
@AllArgsConstructor
public class TaxonomyFacadeServiceImpl implements TaxonomyFacadeService {

    private final TaxonomyMapper taxonomyMapper;

    @Override
    public List<TaxonomyNodeResponse> getTaxonomyTree() {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : taxonomyMapper.countPapersByCode()) {
            counts.put(String.valueOf(row.get("taxonomy_code")), ((Number) row.get("cnt")).longValue());
        }
        return taxonomyMapper.selectAll().stream()
                .map(node -> TaxonomyNodeResponse.builder()
                        .code(node.getCode())
                        .parentCode(node.getParentCode())
                        .nameEn(node.getNameEn())
                        .nameCn(node.getNameCn())
                        .description(node.getDescription())
                        .level(node.getLevel())
                        .paperCount(counts.getOrDefault(node.getCode(), 0L))
                        .build())
                .toList();
    }

    @Override
    public TaxonomyPapersResponse getTaxonomyPapers(String code, int limit) {
        TaxonomyNode node = taxonomyMapper.selectByCode(code);
        if (node == null) {
            throw new BizException("分类节点不存在：" + code);
        }

        Map<String, Long> byCountry = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : taxonomyMapper.paperCountryStatsByCode(code)) {
            String country = row.get("first_author_country") == null ? "未知"
                    : String.valueOf(row.get("first_author_country"));
            long cnt = ((Number) row.get("cnt")).longValue();
            byCountry.put(country, cnt);
            total += cnt;
        }

        List<TaxonomyPapersResponse.PaperBrief> papers = taxonomyMapper.paperListByCode(code, limit).stream()
                .map(row -> TaxonomyPapersResponse.PaperBrief.builder()
                        .docId(String.valueOf(row.get("docid")))
                        .title(row.get("title") == null ? "" : String.valueOf(row.get("title")))
                        .country(row.get("country") == null ? null : String.valueOf(row.get("country")))
                        .publishYear(row.get("publishyear") == null ? null
                                : ((Number) row.get("publishyear")).intValue())
                        .build())
                .toList();

        return TaxonomyPapersResponse.builder()
                .code(code)
                .nameCn(node.getNameCn())
                .total(total)
                .byCountry(byCountry)
                .papers(papers)
                .build();
    }
}
