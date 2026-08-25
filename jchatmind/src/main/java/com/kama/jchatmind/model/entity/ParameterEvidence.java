package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 参数证据实体（对应表 parameter_evidence）
 * 来源：小论文人工审核后的最终参数表（confirmed_parameters_final.csv）
 * 每条记录 = 论文中一处经审核的最终性能参数（含证据原文与页码，可回查）
 */
@Data
@Builder
public class ParameterEvidence {
    private String id;

    /** 小论文抽取决策 ID（幂等键） */
    private String decisionId;

    private String docId;

    /** 技术对象（如 FedLEO、某星座系统名） */
    private String technicalObject;

    private String parameterNameRaw;

    private String parameterNameCanonical;

    /** 规范参数族（如 时延/延迟、吞吐率/数据速率） */
    private String parameterFamily;

    private String valueRaw;

    /** 比较符（>=、<=等，原始表 comparator 列） */
    private String comparator;

    private Double valueMin;

    private Double valueMax;

    private String unitRaw;

    private String unitNormalized;

    /** 测试条件（数据集、网络环境等） */
    private String conditionText;

    /** 证据原文（表格行或句子） */
    private String evidenceText;

    private Integer pageNumber;

    private String section;

    /** table / text 等 */
    private String sourceType;

    /** EASC 原始叶子路径（保留原文，便于回查小论文分类） */
    private String leafPath;

    private String resultForm;

    private String reviewStatus;

    private String firstAuthorCountry;

    private String title;

    /** 从 paper 表富化的发表年份（导入完成后统一回填） */
    private Integer publishYear;

    private LocalDateTime createdAt;
}
