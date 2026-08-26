package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 智能体运行记录实体（对应表 evaluation_run）
 */
@Data
@Builder
public class EvaluationRun {
    private String id;

    private String chatSessionId;

    /** 问题说明（输入） */
    private String question;

    /** 最终专题报告文本 */
    private String report;

    /** Agent 配置快照（JSON：模型、加载的 Skill 等） */
    private String agentConfig;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
