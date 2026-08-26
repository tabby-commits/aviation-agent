package com.kama.jchatmind.evaluation.model.response;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 运行列表条目
 */
@Data
@Builder
public class EvaluationRunSummaryResponse {
    private String id;
    private String chatSessionId;
    private String question;
    private LocalDateTime createdAt;
    /** 检查完成度：已结论项数/8 */
    private String checkProgress;
}
