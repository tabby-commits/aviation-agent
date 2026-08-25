package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 筛选结论导入结果
 */
@Data
@Builder
public class PaperScreeningImportResponse {

    /** 解析出的记录总数 */
    private int total;

    /** 成功更新的论文数 */
    private int updated;

    /** paper 表中不存在该 doc_id 而跳过的行数 */
    private int skipped;

    private int failed;

    /** 失败明细（最多保留 20 条） */
    private List<String> errors;
}
