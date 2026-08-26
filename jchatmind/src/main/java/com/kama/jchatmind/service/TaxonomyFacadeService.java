package com.kama.jchatmind.service;

import com.kama.jchatmind.model.response.TaxonomyNodeResponse;
import com.kama.jchatmind.model.response.TaxonomyPapersResponse;

import java.util.List;

/**
 * 分类体系门面服务
 */
public interface TaxonomyFacadeService {

    /** 全部分类节点（含实时聚合的论文数） */
    List<TaxonomyNodeResponse> getTaxonomyTree();

    /** 节点下的论文列表与国别分布 */
    TaxonomyPapersResponse getTaxonomyPapers(String code, int limit);
}
