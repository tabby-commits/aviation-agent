package com.kama.jchatmind.evaluation.controller;

import com.kama.jchatmind.evaluation.model.request.CreateEvaluationRunRequest;
import com.kama.jchatmind.evaluation.model.request.UpdateCheckItemsRequest;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunDetailResponse;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunSummaryResponse;
import com.kama.jchatmind.evaluation.service.EvaluationRunFacadeService;
import com.kama.jchatmind.model.common.ApiResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行记录与客观检查接口（SPEC 第 5 节）
 */
@RestController
@RequestMapping("/api/evaluations")
@AllArgsConstructor
public class EvaluationRunController {

    private final EvaluationRunFacadeService evaluationRunFacadeService;

    // 登记一次专题分析运行（自动生成八项检查项）
    @PostMapping("/runs")
    public ApiResponse<EvaluationRunDetailResponse> createRun(@RequestBody CreateEvaluationRunRequest request) {
        return ApiResponse.success(evaluationRunFacadeService.createRun(request));
    }

    // 最近运行列表
    @GetMapping("/runs")
    public ApiResponse<List<EvaluationRunSummaryResponse>> listRuns(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(evaluationRunFacadeService.listRuns(limit));
    }

    // 运行详情（含八项检查状态）
    @GetMapping("/runs/{runId}")
    public ApiResponse<EvaluationRunDetailResponse> getRun(@PathVariable String runId) {
        return ApiResponse.success(evaluationRunFacadeService.getRun(runId));
    }

    // 回填最终报告
    @PutMapping("/runs/{runId}/report")
    public ApiResponse<EvaluationRunDetailResponse> updateReport(
            @PathVariable String runId, @RequestBody CreateEvaluationRunRequest request) {
        return ApiResponse.success(evaluationRunFacadeService.updateReport(runId, request.getReport()));
    }

    // 批量登记客观检查结论
    @PutMapping("/runs/{runId}/checks")
    public ApiResponse<EvaluationRunDetailResponse> updateChecks(
            @PathVariable String runId, @RequestBody UpdateCheckItemsRequest request) {
        evaluationRunFacadeService.updateChecks(runId, request);
        return ApiResponse.success(evaluationRunFacadeService.getRun(runId));
    }
}
