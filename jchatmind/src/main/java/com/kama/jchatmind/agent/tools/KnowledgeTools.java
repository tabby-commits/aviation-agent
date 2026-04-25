package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.service.RagService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;

    public KnowledgeTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行混合检索（向量语义 + BM25 关键词，RRF 融合）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行混合检索（RAG）：并行执行向量语义召回与 BM25 关键词召回，使用 RRF 融合后返回 Top N 最相关的知识片段。参数为知识库 ID（kbsId）、查询文本（query）和可选 topN；topN <= 0 时使用系统默认值。"
    )
    public String knowledgeQuery(String kbsId, String query, @Nullable Integer topN) {
        int limit = topN == null ? 0 : topN;
        List<String> strings = ragService.hybridSearch(kbsId, query, limit);
        return String.join("\n", strings);
    }

    public String knowledgeQuery(String kbsId, String query) {
        return knowledgeQuery(kbsId, query, 0);
    }
}
