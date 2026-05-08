package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.hook.AgentHook;
import com.kama.jchatmind.agent.hook.AgentHookContext;
import com.kama.jchatmind.agent.hook.RecoveryBudget;
import com.kama.jchatmind.agent.hook.RecoveryDecision;
import com.kama.jchatmind.agent.hook.RecoveryHookHandler;
import com.kama.jchatmind.agent.hook.ToolRecoverySupport;
import com.kama.jchatmind.config.ContextManagementProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.agent.search.AgenticSearchContext;
import com.kama.jchatmind.agent.tools.ChartTools;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ContextManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer DEFAULT_MAX_STEPS = 20;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    /** 主 Agent 调用 terminate 时，若本条助手可见正文短于该阈值则补跑一轮无工具合成，避免出现仅「让我整理」却无最终答复就结束。 */
    private static final int MIN_MAIN_FINAL_ANSWER_CHARS = 200;

    /**
     * think() 最近一次产出的助手消息（含 toolCalls 与可见正文），供 terminate 后判断是否需补全最终答复。
     */
    private AssistantMessage lastThinkAssistantMessage;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    private ObjectMapper objectMapper;

    private ContextManagementService contextManagementService;

    private String model;

    private AgentRole role = AgentRole.MAIN;

    private boolean persistMessages = true;

    private boolean emitSse = true;

    private int maxSteps = DEFAULT_MAX_STEPS;

    private AgentHook agentHook;

    private ToolRecoverySupport toolRecoverySupport;

    private int currentStepIndex;

    private int finalAnswerSyntheses;

    private final Map<String, Integer> toolRetryCounts = new HashMap<>();

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter
    ) {
        this(agentId, name, description, systemPrompt, null, chatClient, maxMessages, memory, availableTools,
                availableKbs, chatSessionId, sseService, chatMessageFacadeService, chatMessageConverter,
                AgentRole.MAIN, true, true, DEFAULT_MAX_STEPS, null);
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     String model,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     AgentRole role,
                     boolean persistMessages,
                     boolean emitSse,
                     Integer maxSteps
    ) {
        this(agentId, name, description, systemPrompt, model, chatClient, maxMessages, memory, availableTools,
                availableKbs, chatSessionId, sseService, chatMessageFacadeService, chatMessageConverter,
                role, persistMessages, emitSse, maxSteps, null);
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     String model,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     AgentRole role,
                     boolean persistMessages,
                     boolean emitSse,
                     Integer maxSteps,
                     ObjectMapper objectMapper
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.model = model;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.objectMapper = objectMapper;
        this.contextManagementService = new ContextManagementService(new ContextManagementProperties());
        this.role = role == null ? AgentRole.MAIN : role;
        this.persistMessages = persistMessages;
        this.emitSse = emitSse;
        this.maxSteps = maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps;
        this.agentHook = new RecoveryHookHandler();
        this.toolRecoverySupport = new ToolRecoverySupport(this.agentHook);

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     String model,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     AgentRole role,
                     boolean persistMessages,
                     boolean emitSse,
                     Integer maxSteps,
                     ObjectMapper objectMapper,
                     ContextManagementService contextManagementService
    ) {
        this(agentId, name, description, systemPrompt, model, chatClient, maxMessages, memory, availableTools,
                availableKbs, chatSessionId, sseService, chatMessageFacadeService, chatMessageConverter,
                role, persistMessages, emitSse, maxSteps, objectMapper);
        if (contextManagementService != null) {
            this.contextManagementService = contextManagementService;
        }
    }

    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replace("\r", " ").replace("\n", " ");
        return singleLine.length() > 500 ? singleLine.substring(0, 500) : singleLine;
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void saveMessage(Message message) {
        if (!persistMessages) {
            return;
        }
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            saveManagedToolResponseMessage(contextManagementService.manageToolResponseMessage(toolResponseMessage));
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    private void saveManagedToolResponseMessage(ContextManagementService.ManagedToolResponseMessage managedMessage) {
        if (!persistMessages || managedMessage == null || managedMessage.message() == null) {
            return;
        }
        for (ToolResponseMessage.ToolResponse toolResponse : managedMessage.message().getResponses()) {
            ChatMessageDTO.MetaData.MetaDataBuilder metadataBuilder = ChatMessageDTO.MetaData.builder()
                    .toolResponse(toolResponse)
                    .chartArtifacts(extractChartArtifacts(toolResponse));
            ChatMessageDTO.ContextManagement contextManagement =
                    managedMessage.metadataByResponseId().get(toolResponse.id());
            if (contextManagement != null) {
                metadataBuilder.contextManagement(contextManagement);
            }
            ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.TOOL)
                    .content(toolResponse.responseData())
                    .sessionId(this.chatSessionId)
                    .metadata(metadataBuilder.build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        }
    }

    // 从图表工具返回中提取可前端复用的图表元数据
    private List<ChartArtifact> extractChartArtifacts(ToolResponseMessage.ToolResponse toolResponse) {
        if (objectMapper == null || toolResponse == null || !ChartTools.TOOL_NAME.equals(toolResponse.name())) {
            return null;
        }
        try {
            ChartArtifact artifact = objectMapper.readValue(toolResponse.responseData(), ChartArtifact.class);
            if (artifact.getId() == null || artifact.getEchartsOption() == null) {
                return null;
            }
            return List.of(artifact);
        } catch (Exception e) {
            log.warn("Failed to parse chart artifact from tool response: {}", e.getMessage());
            return null;
        }
    }

    private void refreshPendingMessages() {
        if (!emitSse) {
            pendingChatMessages.clear();
            return;
        }
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean think() {
        String commonRules = """
                现在你是一个智能的的具体「决策模块」
                请根据当前对话上下文，决定下一步的动作。
                                \s
                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                """.formatted(this.availableKbs);
        String thinkPrompt = role == AgentRole.MAIN
                ? commonRules + """

                【终止与最终答复（主 Agent）】
                - 当你已收集完知识库/委派搜索等工具结果、准备收束时：必须先在「同一条助手消息」中写出用户可直接阅读的完整最终答复（Markdown，含用户要求的结构/分点/对比等），再考虑是否调用 terminate。
                - 禁止在无实质最终正文时调用 terminate（例如仅写「让我整理一下」「现在可以给出综述了」等过渡语就结束）。
                - 若当前轮适合直接收束且不必再调工具，也可以不调用 terminate，仅输出纯文本答复以结束。
                """
                : commonRules;

        // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
        // 既能让每次 messageList 的最后一条是 本条提示词，
        // 又能够避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(contextManagementService.applyPromptBudget(this.chatMemory.get(this.chatSessionId)))
                .build();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        this.lastThinkAssistantMessage = output;

        // 保存
        saveMessage(output);
        refreshPendingMessages();

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return toolCalls != null && !toolCalls.isEmpty();
    }

    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(contextManagementService.applyPromptBudget(this.chatMemory.get(this.chatSessionId)))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult;
        try {
            emitBeforeToolCalls();
            AgenticSearchContext.set(this.chatSessionId, this.model);
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
        } catch (Exception e) {
            ToolResponseMessage recovered = recoverToolExecutionError(e);
            ContextManagementService.ManagedToolResponseMessage managed =
                    contextManagementService.manageToolResponseMessage(recovered);
            this.chatMemory.add(this.chatSessionId, this.lastThinkAssistantMessage);
            this.chatMemory.add(this.chatSessionId, managed.message());
            saveManagedToolResponseMessage(managed);
            refreshPendingMessages();
            return;
        } finally {
            AgenticSearchContext.clear();
        }

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);
        toolResponseMessage = recoverEmptyToolResponses(toolResponseMessage);
        ContextManagementService.ManagedToolResponseMessage managedToolResponseMessage =
                contextManagementService.manageToolResponseMessage(toolResponseMessage);
        toolResponseMessage = managedToolResponseMessage.message();

        List<Message> conversationHistory = new ArrayList<>(toolExecutionResult.conversationHistory());
        conversationHistory.set(conversationHistory.size() - 1, toolResponseMessage);
        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, conversationHistory);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        saveManagedToolResponseMessage(managedToolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            if (role == AgentRole.MAIN && needsMainAgentFinalAnswerSynthesis(lastThinkAssistantMessage)) {
                synthesizeMainAgentFinalAnswer();
            }
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    private void emitBeforeToolCalls() {
        if (lastThinkAssistantMessage == null || lastThinkAssistantMessage.getToolCalls() == null) {
            return;
        }
        for (AssistantMessage.ToolCall toolCall : lastThinkAssistantMessage.getToolCalls()) {
            agentHook.beforeToolCall(baseHookContext(
                    toolCall.name(),
                    toolCall.arguments(),
                    null,
                    retryCount(toolCall.name())
            ));
            emitHookEvent("before_tool_call", toolCall.name(), null);
        }
    }

    private ToolResponseMessage recoverEmptyToolResponses(ToolResponseMessage toolResponseMessage) {
        ToolResponseMessage recovered = ToolResponseMessage.builder()
                .responses(toolResponseMessage.getResponses()
                        .stream()
                        .map(response -> toolRecoverySupport.recoverEmptyResponse(
                                response,
                                baseHookContext(response.name(), null, null, retryCount(response.name()))
                        ))
                        .toList())
                .build();
        for (ToolResponseMessage.ToolResponse response : recovered.getResponses()) {
            agentHook.afterToolCall(baseHookContext(
                    response.name(),
                    null,
                    response.responseData(),
                    retryCount(response.name())
            ));
            if (toolRecoverySupport.isEmptyResult(findOriginalResponse(toolResponseMessage, response.id()))) {
                int retryCount = retryCount(response.name());
                if (retryCount < RecoveryBudget.defaults().maxToolRetries()) {
                    toolRetryCounts.put(response.name(), retryCount + 1);
                }
                emitHookEvent("tool_empty_result", response.name(), null);
            }
        }
        return recovered;
    }

    private String findOriginalResponse(ToolResponseMessage message, String callId) {
        return message.getResponses()
                .stream()
                .filter(response -> Objects.equals(response.id(), callId))
                .map(ToolResponseMessage.ToolResponse::responseData)
                .findFirst()
                .orElse(null);
    }

    private ToolResponseMessage recoverToolExecutionError(Exception e) {
        if (lastThinkAssistantMessage == null
                || lastThinkAssistantMessage.getToolCalls() == null
                || lastThinkAssistantMessage.getToolCalls().isEmpty()) {
            int retryCount = retryCount("unknownTool");
            if (retryCount < RecoveryBudget.defaults().maxToolRetries()) {
                toolRetryCounts.put("unknownTool", retryCount + 1);
            }
            return toolRecoverySupport.errorResponse(
                    null,
                    "unknownTool",
                    e,
                    baseHookContext("unknownTool", null, null, retryCount)
            );
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : lastThinkAssistantMessage.getToolCalls()) {
            String toolName = toolCall.name();
            int retryCount = retryCount(toolName);
            responses.add(toolRecoverySupport.errorToolResponse(
                    toolCall.id(),
                    toolName,
                    e,
                    baseHookContext(toolName, toolCall.arguments(), null, retryCount)
            ));
            if (retryCount < RecoveryBudget.defaults().maxToolRetries()) {
                toolRetryCounts.put(toolName, retryCount + 1);
            }
            emitHookEvent("tool_error", toolName, null);
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private int retryCount(String toolName) {
        return toolRetryCounts.getOrDefault(toolName, 0);
    }

    private AgentHookContext baseHookContext(String toolName, String toolArguments, Object rawResult, int retryCount) {
        return AgentHookContext.builder()
                .sessionId(this.chatSessionId)
                .agentRole(this.role)
                .stepIndex(this.currentStepIndex)
                .toolName(toolName)
                .toolArguments(toolArguments)
                .rawResult(rawResult)
                .retryCount(retryCount)
                .recoveryBudget(RecoveryBudget.defaults())
                .build();
    }

    private void emitHookEvent(String stage, String toolName, String taskId) {
        if (!emitSse || sseService == null || !StringUtils.hasText(chatSessionId)) {
            return;
        }
        try {
            sseService.send(chatSessionId, SseMessage.builder()
                    .type(SseMessage.Type.AGENT_HOOK_RECOVERY)
                    .payload(SseMessage.Payload.builder()
                            .stage(stage)
                            .toolName(toolName)
                            .taskId(taskId)
                            .build())
                    .build());
        } catch (Exception ignored) {
            // Hook telemetry must not fail the agent.
        }
    }

    private boolean needsMainAgentFinalAnswerSynthesis(AssistantMessage assistant) {
        if (assistant == null) {
            return true;
        }
        String text = assistant.getText();
        if (!StringUtils.hasText(text)) {
            return true;
        }
        return text.trim().length() < MIN_MAIN_FINAL_ANSWER_CHARS;
    }

    /**
     * 在仅过渡语 + terminate 等场景下，基于当前完整对话（含工具返回）补写一条无工具的最终用户可见答复。
     */
    private void synthesizeMainAgentFinalAnswer() {
        if (finalAnswerSyntheses >= RecoveryBudget.defaults().maxFinalAnswerSyntheses()) {
            return;
        }
        RecoveryDecision decision = agentHook.beforeFinalAnswer(baseHookContext(null, null, null, finalAnswerSyntheses));
        emitHookEvent("before_final_answer", null, null);
        finalAnswerSyntheses++;
        try {
            Prompt prompt = Prompt.builder()
                    .chatOptions(this.chatOptions)
                    .messages(contextManagementService.applyPromptBudget(this.chatMemory.get(this.chatSessionId)))
                    .build();
            String synthesisSystem = """
                    你已经完成全部工具检索与委派。请仅根据当前对话中的用户问题与工具返回内容，写出「最终给用户看的」完整答复：
                    - 严格满足用户最初要求的格式（如结构化综述、分点、对比表、时间线等）；
                    - 正文使用 Markdown；不要以「我需要搜索」「正在整理」「让我整理一下」等过程性套话开头，也不要复述本段系统说明；
                    - 禁止调用任何工具，只输出最终正文。
                    """;
            ChatResponse synth = this.chatClient
                    .prompt(prompt)
                    .system(synthesisSystem)
                    .call()
                    .chatClientResponse()
                    .chatResponse();
            Assert.notNull(synth, "Synthesis chat response cannot be null");
            AssistantMessage out = synth.getResult().getOutput();
            if (out == null) {
                log.warn("Synthesis produced null assistant output");
                return;
            }
            if (out.getToolCalls() != null && !out.getToolCalls().isEmpty()) {
                log.warn("Synthesis round requested tools; dropping tool calls and persisting text only");
                out = AssistantMessage.builder()
                        .content(out.getText())
                        .build();
            }
            this.lastChatResponse = synth;
            saveMessage(out);
            this.chatMemory.add(this.chatSessionId, out);
            refreshPendingMessages();
        } catch (Exception e) {
            log.warn("Main agent final-answer synthesis failed after hook {}: {}", decision.action(), e.getMessage());
        }
    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < maxSteps && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                int currentStep = i + 1;
                this.currentStepIndex = currentStep;
                step();
                if (currentStep >= maxSteps) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentHook.onTaskCompleted(baseHookContext(null, null, null, 0));
            emitHookEvent("task_completed", null, null);
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        }
    }

    public String getLastAssistantText() {
        if (lastChatResponse == null || lastChatResponse.getResult() == null) {
            return null;
        }
        AssistantMessage output = lastChatResponse.getResult().getOutput();
        return output == null ? null : output.getText();
    }

    public String getConversationTextForRepair() {
        return this.chatMemory.get(this.chatSessionId)
                .stream()
                .map(message -> {
                    if (message instanceof AssistantMessage assistantMessage) {
                        return "assistant: " + assistantMessage.getText();
                    }
                    if (message instanceof UserMessage userMessage) {
                        return "user: " + userMessage.getText();
                    }
                    if (message instanceof SystemMessage systemMessage) {
                        return "system: " + systemMessage.getText();
                    }
                    if (message instanceof ToolResponseMessage toolResponseMessage) {
                        String responses = toolResponseMessage.getResponses()
                                .stream()
                                .map(resp -> resp.name() + "=" + preview(resp.responseData()))
                                .collect(Collectors.joining("; "));
                        return "tool: " + responses;
                    }
                    return message.getMessageType() + ": " + message.getText();
                })
                .collect(Collectors.joining("\n"));
    }

    public AgentRole getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
