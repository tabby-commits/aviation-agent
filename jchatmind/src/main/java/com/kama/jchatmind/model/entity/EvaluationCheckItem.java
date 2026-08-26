package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 客观检查项实体（对应表 evaluation_check_item）
 * status：pending / 符合 / 不符合 / 证据不足
 */
@Data
@Builder
public class EvaluationCheckItem {
    private String id;

    private String runId;

    /** 八项检查之一（见 EvaluationFacadeService.CHECK_ITEMS） */
    private String itemKey;

    private String status;

    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
