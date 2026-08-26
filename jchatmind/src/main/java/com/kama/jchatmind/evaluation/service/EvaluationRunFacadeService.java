package com.kama.jchatmind.evaluation.service;

import com.kama.jchatmind.evaluation.model.request.CreateEvaluationRunRequest;
import com.kama.jchatmind.evaluation.model.request.UpdateCheckItemsRequest;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunDetailResponse;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunSummaryResponse;

import java.util.List;

/**
 * 智能体运行记录与客观检查门面服务（中期报告 3.4 节）
 */
public interface EvaluationRunFacadeService {

    /** 登记一次专题分析运行（自动生成八项客观检查项 pending） */
    EvaluationRunDetailResponse createRun(CreateEvaluationRunRequest request);

    /** 运行详情（含检查项） */
    EvaluationRunDetailResponse getRun(String runId);

    /** 最近运行列表 */
    List<EvaluationRunSummaryResponse> listRuns(int limit);

    /** 更新报告文本（报告生成后回填） */
    EvaluationRunDetailResponse updateReport(String runId, String report);

    /** 批量登记检查结论（符合/不符合/证据不足 + 备注），按 item_key 幂等 */
    List<EvaluationCheckItemView> updateChecks(String runId, UpdateCheckItemsRequest request);

    /** 检查项视图（服务层复用） */
    record EvaluationCheckItemView(String itemKey, String title, String status, String note) {
    }

    /** 八项客观检查清单（key → 标题） */
    List<String[]> CHECK_ITEMS = List.of(
            new String[]{"dimension_coverage", "维度覆盖：竞争力维度是否覆盖问题"},
            new String[]{"indicator_rationale", "指标选择理由：所选指标是否给出理由与未选说明"},
            new String[]{"citation_completeness", "引用完整性：关键判断是否带论文/参数/分块引用"},
            new String[]{"source_level", "来源层级：官方/学术/企业/新闻证据角色是否适当"},
            new String[]{"retrieval_traceable", "检索可追溯：检索范围、工具与版本信息是否保留"},
            new String[]{"method_reproducible", "方法可复核：方法调用与关键计算是否可复算"},
            new String[]{"conclusion_evidence", "结论对应证据：结论是否由所列证据支撑"},
            new String[]{"boundary_statement", "边界表述：适用范围与数据缺口是否明确"});
}
