package com.kama.jchatmind.evaluation.controller;

import com.kama.jchatmind.evaluation.request.ExportEvaluationRetrievalRequest;
import com.kama.jchatmind.evaluation.response.ExportEvaluationRetrievalResponse;
import com.kama.jchatmind.evaluation.service.EvaluationRetrievalExportService;
import com.kama.jchatmind.model.common.ApiResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evaluations")
@AllArgsConstructor
public class EvaluationController {

    private final EvaluationRetrievalExportService evaluationRetrievalExportService;

    @PostMapping("/retrieval/export")
    public ApiResponse<ExportEvaluationRetrievalResponse> exportRetrieval(
            @RequestBody ExportEvaluationRetrievalRequest request) {
        return ApiResponse.success(evaluationRetrievalExportService.exportRetrieval(request));
    }
}
