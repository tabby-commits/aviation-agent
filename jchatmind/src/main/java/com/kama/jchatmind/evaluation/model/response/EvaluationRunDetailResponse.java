package com.kama.jchatmind.evaluation.model.response;

import com.kama.jchatmind.evaluation.service.EvaluationRunFacadeService;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 运行详情响应（含检查项）
 */
@Data
@Builder
public class EvaluationRunDetailResponse {
    private String id;
    private String chatSessionId;
    private String question;
    private String report;
    private String agentConfig;
    private LocalDateTime createdAt;
    private List<EvaluationRunFacadeService.EvaluationCheckItemView> checks;
}
