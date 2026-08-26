package com.kama.jchatmind.service;

import com.kama.jchatmind.model.response.CorpusImportResponse;

/**
 * 论文全文入库服务（SPEC 实施顺序 ③）
 */
public interface PaperCorpusService {

    /**
     * 批量导入论文全文到 RAG（同步分批，幂等：按 KB+文件名跳过已导入）
     *
     * @param pdfDir PDF 所在目录（文件名与 paper.file_name 对应）
     * @param limit  本批最多处理篇数
     */
    CorpusImportResponse importCorpus(String pdfDir, int limit);
}
