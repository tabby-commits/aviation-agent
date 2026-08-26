package com.kama.jchatmind.evaluation.model.request;

import lombok.Data;

/**
 * 登记运行请求
 */
@Data
public class CreateEvaluationRunRequest {
    private String chatSessionId;

    /** 问题说明（必填） */
    private String question;

    /** 最终报告（可后补） */
    private String report;

    /** Agent 配置快照 JSON（可选） */
    private String agentConfig;
}
