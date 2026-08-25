package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 针对表【paper】的数据库操作 Mapper
 */
@Mapper
public interface PaperMapper {

    /** 按 doc_id 插入或更新（ON CONFLICT DO UPDATE），返回影响行数 */
    int upsert(Paper paper);

    Paper selectByDocId(@Param("docId") String docId);

    int countByDocId(@Param("docId") String docId);

    /** 删除 doc_id 以 prefix 开头的记录（测试清理用） */
    int deleteByDocIdPrefix(@Param("prefix") String prefix);
}
