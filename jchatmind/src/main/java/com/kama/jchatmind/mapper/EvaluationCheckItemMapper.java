package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.EvaluationCheckItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 针对表【evaluation_check_item】的数据库操作 Mapper
 */
@Mapper
public interface EvaluationCheckItemMapper {

    /** 插入或更新检查结论（按 run_id+item_key 幂等） */
    int upsert(EvaluationCheckItem item);

    List<EvaluationCheckItem> selectByRunId(@Param("runId") String runId);

    int deleteByRunId(@Param("runId") String runId);
}
