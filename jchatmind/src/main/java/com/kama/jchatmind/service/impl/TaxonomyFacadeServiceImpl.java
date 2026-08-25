package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.TaxonomyMapper;
import com.kama.jchatmind.model.response.TaxonomyNodeResponse;
import com.kama.jchatmind.service.TaxonomyFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类体系门面服务：节点查询与论文计数聚合
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
}
