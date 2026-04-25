package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.evaluation.model.StructuredRetrievalResult;
import com.kama.jchatmind.evaluation.service.StructuredRetrievalService;
import org.springframework.stereotype.Component;

@Component
public class SubAgentKnowledgeTool implements SubAgentOnlyTool {

    private final StructuredRetrievalService structuredRetrievalService;
    private final AgenticSearchProperties properties;
    private final ObjectMapper objectMapper;

    public SubAgentKnowledgeTool(StructuredRetrievalService structuredRetrievalService,
                                 AgenticSearchProperties properties,
                                 ObjectMapper objectMapper) {
        this.structuredRetrievalService = structuredRetrievalService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "knowledgeWideQuery";
    }

    @Override
    public String getDescription() {
        return "子 Agent 专属知识库宽召回工具，返回结构化 JSON，包含 chunkId/docId/content/score/rank 等字段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "knowledgeWideQuery",
            description = "从指定知识库执行宽召回。参数：kbId 知识库 ID；query 查询文本；topK 返回条数，<=0 使用默认值。返回结构化 JSON。"
    )
    public String knowledgeWideQuery(String kbId, String query, Integer topK) {
        int limit = clampTopK(topK);
        StructuredRetrievalResult result = structuredRetrievalService.retrieve(kbId, query, limit);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化知识库宽召回结果失败", e);
        }
    }

    private int clampTopK(Integer topK) {
        int requested = topK == null || topK <= 0
                ? properties.getSubAgent().getKbWideTopK()
                : topK;
        return Math.min(requested, properties.getSubAgent().getKbWideTopKMax());
    }
}
