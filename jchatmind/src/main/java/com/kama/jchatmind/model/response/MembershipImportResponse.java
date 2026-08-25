package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 论文分类归属导入结果
 */
@Data
@Builder
public class MembershipImportResponse {

    /** 解析出的记录总数 */
    private int total;

    /** 新导入的归属数 */
    private int imported;

    /** candidate_cluster（弱关联）跳过数 */
    private int candidateSkipped;

    /** 一级类目未命中映射跳过数 */
    private int unmappedSkipped;

    /** paper 表中无此 doc_id 跳过数 */
    private int noPaperSkipped;

    private int failed;

    /** 失败/未匹配明细（最多保留 20 条） */
    private List<String> errors;
}
