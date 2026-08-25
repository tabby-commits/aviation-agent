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

    /** 按分类 code 删除（测试清理用） */
    int deleteByTaxonomyCode(@Param("code") String code);

    /** 按来源删除（测试清理用） */
    int deleteBySource(@Param("source") String source);

    int countByCode(@Param("code") String code);
}
