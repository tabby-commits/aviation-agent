package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.EvaluationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 针对表【evaluation_run】的数据库操作 Mapper
 */
@Mapper
public interface EvaluationRunMapper {

    int insert(EvaluationRun run);

    EvaluationRun selectById(@Param("id") String id);

    List<EvaluationRun> selectAll(@Param("limit") int limit);

    int updateReport(@Param("id") String id, @Param("report") String report);
}
