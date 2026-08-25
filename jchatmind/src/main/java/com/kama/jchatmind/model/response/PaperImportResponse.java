package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 论文元数据导入结果
 */
@Data
@Builder
public class PaperImportResponse {

    private String sourceDb;

    /** 解析出的记录总数 */
    private int total;

    private int inserted;

    private int updated;

    private int failed;

    /** 失败明细（最多保留 20 条） */
    private List<String> errors;
}
