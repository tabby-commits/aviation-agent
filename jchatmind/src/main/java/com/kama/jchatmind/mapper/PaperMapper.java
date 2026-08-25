package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

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

    /** 按筛选结论更新（screening_status/国别/证据/置信度/文件名/排除原因，空值不覆盖），返回影响行数 */
    int updateScreening(@Param("paper") Paper paper);

    /** 条件分页查询（按 publish_year DESC, doc_id ASC 排序） */
    List<Paper> selectByCondition(PaperQueryRequest query);

    long countByCondition(PaperQueryRequest query);

    /** 按来源库分组计数，返回列 source_db / cnt */
    List<Map<String, Object>> countGroupBySourceDb();

    /** 按筛选状态分组计数，返回列 screening_status / cnt */
    List<Map<String, Object>> countGroupByScreeningStatus();
}
