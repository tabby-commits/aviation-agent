package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ParameterEvidence;
import com.kama.jchatmind.model.request.ParameterQueryRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 针对表【parameter_evidence】的数据库操作 Mapper
 */
@Mapper
public interface ParameterEvidenceMapper {

    /** 按 decision_id 插入或更新（幂等），返回影响行数 */
    int upsert(ParameterEvidence evidence);

    ParameterEvidence selectByDecisionId(@Param("decisionId") String decisionId);

    List<ParameterEvidence> selectByCondition(ParameterQueryRequest query);

    long countByCondition(ParameterQueryRequest query);

    /** 用 paper 表回填 publish_year（导入完成后执行一次），返回更新行数 */
    int enrichPublishYear();

    /** 删除 decision_id 以 prefix 开头的记录（测试清理用） */
    int deleteByDecisionIdPrefix(@Param("prefix") String prefix);
}
