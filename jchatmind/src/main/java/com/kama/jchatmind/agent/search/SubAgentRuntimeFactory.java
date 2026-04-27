package com.kama.jchatmind.agent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentRole;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.search.model.SubTaskResult;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class SubAgentRuntimeFactory {

    private final ChatClientRegistry chatClientRegistry;
    private final SubAgentToolsetPolicy toolsetPolicy;
    private final AgenticSearchProperties properties;
    private final ObjectMapper objectMapper;

    public SubAgentRuntimeFactory(ChatClientRegistry chatClientRegistry,
                                  SubAgentToolsetPolicy toolsetPolicy,
                                  AgenticSearchProperties properties,
                                  ObjectMapper objectMapper) {
        this.chatClientRegistry = chatClientRegistry;
        this.toolsetPolicy = toolsetPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SubTaskResult runSubAgent(SubTaskSpec spec,
                                     String parentSessionId,
                                     String model,
                                     int maxSteps,
                                     String fallback) {
        JChatMind runtime = create(spec, parentSessionId, model, maxSteps, fallback);
        runtime.run();
        String output = runtime.getLastAssistantText();
        if (StringUtils.hasText(output)) {
            try {
                return parseResult(extractJson(output));
            } catch (IllegalStateException ignored) {
                // Fall through to one no-tool repair round below.
            }
        }
        String repairedOutput = repairJsonOutput(spec, model, fallback, runtime.getConversationTextForRepair(), output);
        return parseResult(extractJson(repairedOutput));
    }

    private JChatMind create(SubTaskSpec spec,
                             String parentSessionId,
                             String model,
                             int maxSteps,
                             String fallback) {
        String effectiveModel = StringUtils.hasText(model) ? model : properties.getRouter().getModel();
        ChatClient chatClient = resolveChatClient(effectiveModel);
        List<Tool> tools = toolsetPolicy.resolve(spec);
        List<ToolCallback> callbacks = buildToolCallbacksFromTools(tools);
        String subSessionId = (StringUtils.hasText(parentSessionId) ? parentSessionId : "agentic")
                + ":sub:" + spec.taskId() + ":" + UUID.randomUUID();
        List<Message> memory = List.of(new UserMessage(buildTaskPrompt(spec, fallback)));

        return new JChatMind(
                "sub-agent-" + spec.taskId(),
                "Agentic Search SubAgent",
                "Delegated search worker",
                subAgentSystemPrompt(),
                effectiveModel,
                chatClient,
                20,
                memory,
                callbacks,
                List.of(),
                subSessionId,
                null,
                null,
                null,
                AgentRole.SUB,
                false,
                false,
                maxSteps
        );
    }

    private SubTaskResult parseResult(String json) {
        try {
            return objectMapper.readValue(json, SubTaskResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析子 Agent JSON 输出失败", e);
        }
    }

    private String repairJsonOutput(SubTaskSpec spec,
                                    String model,
                                    String fallback,
                                    String conversation,
                                    String previousOutput) {
        String effectiveModel = StringUtils.hasText(model) ? model : properties.getRouter().getModel();
        ChatClient chatClient = resolveChatClient(effectiveModel);
        String content = chatClient.prompt()
                .system(subAgentSystemPrompt() + """

                        你现在处于最终修复轮，不能调用任何工具。
                        必须仅输出一个 JSON 对象；即使证据不足，也要输出 JSON，并在 summary 中说明证据不足。
                        """)
                .user("""
                        子任务信息：
                        taskId: %s
                        taskDescription: %s
                        kbId: %s
                        scope: %s
                        searchPolicy: %s
                        fallback: %s

                        子 Agent 执行过程：
                        %s

                        上一次最终输出：
                        %s

                        请只输出符合 SubTaskResult 的 JSON 对象。
                        """.formatted(
                        spec.taskId(),
                        spec.taskDescription(),
                        spec.kbId(),
                        spec.scope(),
                        spec.searchPolicy(),
                        fallback,
                        conversation,
                        previousOutput
                ))
                .call()
                .content();
        return content;
    }

    private ChatClient resolveChatClient(String model) {
        ChatClient chatClient = chatClientRegistry.get(model);
        if (chatClient == null) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + model);
        }
        return chatClient;
    }

    private List<ToolCallback> buildToolCallbacksFromTools(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(tool)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
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

    private String buildTaskPrompt(SubTaskSpec spec, String fallback) {
        return """
                请执行以下委派检索任务，并最终只输出一个符合 SubTaskResult 的 JSON 对象。

                taskId: %s
                taskDescription: %s
                kbId: %s
                scope: %s
                searchPolicy: %s
                fallback: %s
                """.formatted(
                spec.taskId(),
                spec.taskDescription(),
                spec.kbId(),
                spec.scope(),
                spec.searchPolicy(),
                fallback
        );
    }

    private String subAgentSystemPrompt() {
        return """
                你是 Agentic Search 的子 Agent，只负责完成一个独立检索子任务。
                你只能使用当前提供的工具，不得尝试再次委派任务。
                最终回答必须是严格 JSON，结构为：
                {
                  "taskId": "...",
                  "status": "OK",
                  "summary": "...",
                  "keyFindings": [{"title": "...", "detail": "...", "citations": ["kb:1"]}],
                  "citations": [{"id": "kb:1", "title": "...", "docId": "...", "chunkId": "...", "snippet": "...", "sourceType": "kb"}],
                  "stats": {"toolCalls": 0, "kbQueries": 0, "webQueries": 0, "elapsedMs": 0, "fallback": null}
                }
                禁止输出 Markdown 代码块或 JSON 以外的解释。
                不要调用 terminate 或 directAnswer 作为最终回答；最终 JSON 必须作为普通 assistant 文本直接输出。
                如果检索工具返回空结果、degraded=true 或 errorMessage 非空，不要反复重试到耗尽步数；
                请基于已获得的工具结果输出 JSON，summary 中说明证据不足，citations 可为空数组。
                """;
    }
}
