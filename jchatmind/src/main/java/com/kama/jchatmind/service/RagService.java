package com.kama.jchatmind.service;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    List<String> similaritySearch(String kbId, String title);

    /**
     * 混合召回：并行执行向量召回与 BM25 召回，RRF 融合后返回 Top N 的 chunk content。
     * 若 {@code jchatmind.rag.hybrid.enabled=false} 或 BM25 索引为空，回退到纯向量召回。
     *
     * @param kbId  知识库 ID
     * @param query 用户查询
     * @param topN  最终返回条数；≤0 时使用配置默认值
     */
    List<String> hybridSearch(String kbId, String query, int topN);
}
