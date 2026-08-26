package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.PaperTaxonomy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 针对表【paper_taxonomy】的数据库操作 Mapper
 */
@Mapper
public interface PaperTaxonomyMapper {

    /** 插入，唯一约束冲突时忽略（幂等），返回影响行数（0=已存在） */
    int insertIgnore(PaperTaxonomy paperTaxonomy);

    List<PaperTaxonomy> selectByDocId(@Param("docId") String docId);

    List<PaperTaxonomy> selectByTaxonomyCode(@Param("code") String code);

    /** 按分类 code 删除（测试清理用） */
    int deleteByTaxonomyCode(@Param("code") String code);

    /** 按来源删除（测试清理用；注意与真实数据共用 source 时会连带删除真实数据） */
    int deleteBySource(@Param("source") String source);

    /** 删除 doc_id 以 prefix 开头的归属（测试清理安全方法） */
    int deleteByDocIdPrefix(@Param("prefix") String prefix);

    int countByCode(@Param("code") String code);
}
