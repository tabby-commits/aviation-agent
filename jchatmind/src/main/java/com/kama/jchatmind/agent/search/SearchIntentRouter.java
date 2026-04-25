package com.kama.jchatmind.agent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class SearchIntentRouter {

    private final ChatClientRegistry chatClientRegistry;
    private final AgenticSearchProperties properties;
    private final ObjectMapper objectMapper;

    public SearchIntentRouter(ChatClientRegistry chatClientRegistry,
                              AgenticSearchProperties properties,
                              ObjectMapper objectMapper) {
        this.chatClientRegistry = chatClientRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SearchIntentDecision route(String userInput, List<String> conversationTail) {
        if (!properties.isEnabled() || !properties.getRouter().isEnabled()) {
            return SearchIntentDecision.disabled("disabled");
        }
        if (!StringUtils.hasText(userInput)) {
            return SearchIntentDecision.disabled("empty_input");
        }
        try {
            ChatClient chatClient = chatClientRegistry.get(properties.getRouter().getModel());
            if (chatClient == null) {
                return SearchIntentDecision.disabled("router_model_missing");
            }
            String content = chatClient.prompt()
                    .system(routerSystemPrompt())
                    .user(buildUserPrompt(userInput, conversationTail))
                    .call()
                    .content();
            return parse(content);
        } catch (Exception e) {
            return SearchIntentDecision.disabled("router_error");
        }
    }

    private SearchIntentDecision parse(String content) throws Exception {
        if (!StringUtils.hasText(content)) {
            return SearchIntentDecision.disabled("empty_router_response");
        }
        String json = extractJson(content);
        return objectMapper.readValue(json, SearchIntentDecision.class);
    }

    private String extractJson(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String routerSystemPrompt() {
        return """
                你是搜索意图路由器。判断用户问题是否需要 Agentic Search。
                仅当问题属于综述、广搜、跨来源分析、时间序列汇总、复杂比较时 useAgenticSearch=true。
                简单事实问答、闲聊、单点知识库查询应返回 false。
                只输出 JSON：
                {"useAgenticSearch": true|false, "reason": "survey|time_series|multi_source|comparison|none", "needClarification": true|false}
                """;
    }

    private String buildUserPrompt(String userInput, List<String> conversationTail) {
        return """
                userInput:
                %s

                conversationTail:
                %s
                """.formatted(userInput, conversationTail == null ? List.of() : conversationTail);
    }
}
