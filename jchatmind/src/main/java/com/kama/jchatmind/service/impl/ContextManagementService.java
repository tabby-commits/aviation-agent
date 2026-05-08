package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ContextManagementProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextManagementService {
    private static final String CHART_TOOL_NAME = "generateChart";
    private static final String SUMMARY_PREFIX = "【历史对话摘要】";
    private static final String OMISSION_MESSAGE = "【上下文管理】早期上下文已省略，仅保留系统提示、历史摘要和最近消息。";

    private final ContextManagementProperties properties;

    public ManagedToolResponse manageToolResponse(ToolResponseMessage.ToolResponse response) {
        if (!properties.isEnabled()
                || response == null
                || response.responseData() == null
                || CHART_TOOL_NAME.equals(response.name())
                || response.responseData().length() <= properties.getToolResultMaxChars()) {
            return new ManagedToolResponse(response, null);
        }

        String raw = response.responseData();
        int headChars = Math.max(0, Math.min(properties.getToolResultHeadChars(), raw.length()));
        int tailChars = Math.max(0, Math.min(properties.getToolResultTailChars(), raw.length() - headChars));
        String compressed = raw.substring(0, headChars)
                + "\n...[truncated]...\n"
                + raw.substring(raw.length() - tailChars);

        ToolResponseMessage.ToolResponse compressedResponse = new ToolResponseMessage.ToolResponse(
                response.id(),
                response.name(),
                compressed
        );
        ChatMessageDTO.ContextManagement metadata = ChatMessageDTO.ContextManagement.builder()
                .compressionType("TOOL_RESULT_TRUNCATED")
                .originalLength(raw.length())
                .retainedLength(compressed.length())
                .internalMessage(false)
                .build();
        return new ManagedToolResponse(compressedResponse, metadata);
    }

    public ManagedToolResponseMessage manageToolResponseMessage(ToolResponseMessage message) {
        if (message == null || message.getResponses() == null || message.getResponses().isEmpty()) {
            return new ManagedToolResponseMessage(message, Map.of());
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        Map<String, ChatMessageDTO.ContextManagement> metadataByResponseId = new LinkedHashMap<>();
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            ManagedToolResponse managed = manageToolResponse(response);
            responses.add(managed.response());
            if (managed.contextManagement() != null) {
                metadataByResponseId.put(response.id(), managed.contextManagement());
            }
        }
        return new ManagedToolResponseMessage(
                ToolResponseMessage.builder().responses(responses).build(),
                metadataByResponseId
        );
    }

    public boolean shouldSummarize(List<ChatMessageDTO> messagesAfterLatestSummary) {
        return properties.isEnabled()
                && messagesAfterLatestSummary != null
                && messagesAfterLatestSummary.size() >= properties.getSummaryTriggerMessages();
    }

    public int summaryScanLimit() {
        return Math.max(1, properties.getSummaryTriggerMessages())
                + Math.max(0, properties.getSummaryKeepRecentMessages())
                + 1;
    }

    public List<ChatMessageDTO> selectMessagesToSummarize(List<ChatMessageDTO> messagesAfterLatestSummary) {
        if (messagesAfterLatestSummary == null || messagesAfterLatestSummary.isEmpty()) {
            return List.of();
        }
        int keepRecent = Math.max(0, properties.getSummaryKeepRecentMessages());
        int endExclusive = Math.max(1, messagesAfterLatestSummary.size() - keepRecent);
        return new ArrayList<>(messagesAfterLatestSummary.subList(0, endExclusive));
    }

    public ChatMessageDTO createSummaryMessage(String sessionId,
                                               List<ChatMessageDTO> messages,
                                               Function<String, String> modelSummarizer) {
        List<ChatMessageDTO> ordered = messages == null
                ? List.of()
                : messages.stream()
                .sorted(Comparator.comparing(ChatMessageDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String prompt = buildSummaryPrompt(ordered);
        String summary;
        try {
            summary = modelSummarizer.apply(prompt);
        } catch (Exception ignored) {
            summary = ruleBasedSummary(ordered);
        }
        if (!StringUtils.hasText(summary)) {
            summary = ruleBasedSummary(ordered);
        }

        ChatMessageDTO last = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
        return ChatMessageDTO.builder()
                .sessionId(sessionId)
                .role(ChatMessageDTO.RoleType.SYSTEM)
                .content(SUMMARY_PREFIX + "\n" + summary.trim())
                .metadata(ChatMessageDTO.MetaData.builder()
                        .contextManagement(ChatMessageDTO.ContextManagement.builder()
                                .compressionType("CONVERSATION_SUMMARY")
                                .originalLength(prompt.length())
                                .retainedLength(summary.length())
                                .coveredUntilMessageId(last == null ? null : last.getId())
                                .internalMessage(true)
                                .build())
                        .build())
                .build();
    }

    public List<Message> applyPromptBudget(List<Message> messages) {
        if (!properties.isEnabled() || messages == null || promptLength(messages) <= properties.getMaxPromptChars()) {
            return messages;
        }

        List<Message> systemMessages = new ArrayList<>();
        Message latestSummary = null;
        Message latestUser = null;
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                systemMessages.add(message);
                if (isSummaryMessage(message)) {
                    latestSummary = message;
                }
            }
            if (message.getMessageType() != null && "USER".equalsIgnoreCase(message.getMessageType().name())) {
                latestUser = message;
            }
        }

        List<Message> managed = new ArrayList<>();
        for (Message system : systemMessages) {
            if (!isSummaryMessage(system) || system == latestSummary) {
                managed.add(system);
            }
        }
        if (latestSummary != null && !managed.contains(latestSummary)) {
            managed.add(latestSummary);
        }
        managed.add(new SystemMessage(OMISSION_MESSAGE));
        if (latestUser != null && !managed.contains(latestUser)) {
            managed.add(latestUser);
        }
        return managed;
    }

    public boolean isInternalMessage(ChatMessageDTO dto) {
        return dto != null
                && dto.getMetadata() != null
                && dto.getMetadata().getContextManagement() != null
                && dto.getMetadata().getContextManagement().isInternalMessage();
    }

    public boolean isSummaryMessage(ChatMessageDTO dto) {
        return isInternalMessage(dto)
                && "CONVERSATION_SUMMARY".equals(dto.getMetadata().getContextManagement().getCompressionType());
    }

    private boolean isSummaryMessage(Message message) {
        return message != null && StringUtils.hasText(message.getText()) && message.getText().startsWith(SUMMARY_PREFIX);
    }

    private int promptLength(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .mapToInt(String::length)
                .sum();
    }

    private String buildSummaryPrompt(List<ChatMessageDTO> messages) {
        return """
                请把下面的早期对话压缩为一段可供后续 Agent 使用的上下文摘要。
                要求：保留用户目标、关键约束、工具结论、尚未完成事项；不要编造；使用中文。

                %s
                """.formatted(messages.stream()
                .map(message -> message.getRole().getRole() + ": " + abbreviate(message.getContent(), 1200))
                .collect(Collectors.joining("\n")));
    }

    private String ruleBasedSummary(List<ChatMessageDTO> messages) {
        if (messages.isEmpty()) {
            return "暂无可摘要的早期对话。";
        }
        return messages.stream()
                .map(message -> message.getRole().getRole() + ": " + abbreviate(message.getContent(), 300))
                .collect(Collectors.joining("\n"));
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replace("\r", " ").replace("\n", " ");
        return singleLine.length() > maxChars ? singleLine.substring(0, maxChars) : singleLine;
    }

    public record ManagedToolResponse(
            ToolResponseMessage.ToolResponse response,
            ChatMessageDTO.ContextManagement contextManagement
    ) {
    }

    public record ManagedToolResponseMessage(
            ToolResponseMessage message,
            Map<String, ChatMessageDTO.ContextManagement> metadataByResponseId
    ) {
    }
}
