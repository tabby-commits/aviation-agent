package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 论文全文批量导入结果（分批同步模式，调用方循环调用直至 remaining=0）
 */
@Data
@Builder
public class CorpusImportResponse {

    /** 论文全文知识库 id */
    private String kbId;

    /** 本批成功处理论文数 */
    private int processed;

    /** 本批新建分块数（含嵌入） */
    private int chunksCreated;

    /** 处理失败论文数 */
    private int failed;

    /** PDF 文件在目录中缺失的论文数 */
    private int missing;

    /** 已导入过而跳过的论文数 */
    private int skippedExisting;

    /** 全部剩余待导入论文数（含本批） */
    private int remaining;

    /** 失败明细（最多保留 20 条） */
    private List<String> errors;
}
