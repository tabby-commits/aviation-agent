package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.TaxonomyNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 针对表【taxonomy_node】的数据库操作 Mapper
 */
@Mapper
public interface TaxonomyMapper {

    /** 全量节点（按 level, code 排序） */
    List<TaxonomyNode> selectAll();

    TaxonomyNode selectByCode(@Param("code") String code);

    /** 各分类的已归属论文数，返回列 taxonomy_code / cnt */
    List<Map<String, Object>> countPapersByCode();

    /** 节点下论文的国别分布，返回列 first_author_country / cnt */
    List<Map<String, Object>> paperCountryStatsByCode(@Param("code") String code);

    /** 节点下论文列表（doc_id/标题/国别/年份），按年份降序 */
    List<Map<String, Object>> paperListByCode(@Param("code") String code, @Param("limit") int limit);
}
