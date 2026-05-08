package com.kama.jchatmind.service;

import com.kama.jchatmind.config.ContextManagementProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.impl.ContextManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagementServiceTest {

    @Test
    void truncatesLongToolResponseAndRecordsMetadata() {
        ContextManagementProperties properties = new ContextManagementProperties();
        properties.setToolResultMaxChars(20);
        properties.setToolResultHeadChars(8);
        properties.setToolResultTailChars(8);
        ContextManagementService service = new ContextManagementService(properties);

        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                "call-1",
                "readFile",
                "HEAD-1234567890-MIDDLE-abcdefghij-TAIL"
        );

        ContextManagementService.ManagedToolResponse managed = service.manageToolResponse(response);

        assertThat(managed.response().responseData()).contains("HEAD-123");
        assertThat(managed.response().responseData()).contains("ij-TAIL");
        assertThat(managed.response().responseData()).doesNotContain("MIDDLE");
        assertThat(managed.contextManagement()).isNotNull();
        assertThat(managed.contextManagement().getCompressionType()).isEqualTo("TOOL_RESULT_TRUNCATED");
        assertThat(managed.contextManagement().getOriginalLength()).isEqualTo(response.responseData().length());
        assertThat(managed.contextManagement().getRetainedLength()).isLessThan(response.responseData().length());
        assertThat(managed.contextManagement().isInternalMessage()).isFalse();
    }

    @Test
    void keepsShortToolResponseUnchangedWithoutMetadata() {
        ContextManagementService service = new ContextManagementService(new ContextManagementProperties());
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                "call-1",
                "search",
                "short result"
        );

        ContextManagementService.ManagedToolResponse managed = service.manageToolResponse(response);

        assertThat(managed.response()).isEqualTo(response);
        assertThat(managed.contextManagement()).isNull();
    }

    @Test
    void fallsBackToRuleSummaryWhenModelSummaryFails() {
        ContextManagementProperties properties = new ContextManagementProperties();
        properties.setSummaryKeepRecentMessages(1);
        ContextManagementService service = new ContextManagementService(properties);
        List<ChatMessageDTO> messages = List.of(
                message("m1", ChatMessageDTO.RoleType.USER, "请分析第一个问题", 1),
                message("m2", ChatMessageDTO.RoleType.ASSISTANT, "第一个回答包含关键结论", 2),
                message("m3", ChatMessageDTO.RoleType.USER, "请继续分析第二个问题", 3)
        );

        ChatMessageDTO summary = service.createSummaryMessage(
                "session-1",
                messages,
                prompt -> {
                    throw new IllegalStateException("model unavailable");
                }
        );

        assertThat(summary.getRole()).isEqualTo(ChatMessageDTO.RoleType.SYSTEM);
        assertThat(summary.getContent()).contains("历史对话摘要");
        assertThat(summary.getContent()).contains("请分析第一个问题");
        assertThat(summary.getMetadata().getContextManagement().isInternalMessage()).isTrue();
        assertThat(summary.getMetadata().getContextManagement().getCompressionType()).isEqualTo("CONVERSATION_SUMMARY");
        assertThat(summary.getMetadata().getContextManagement().getCoveredUntilMessageId()).isEqualTo("m3");
    }

    @Test
    void forcedTruncationKeepsSystemSummaryAndLatestUserMessage() {
        ContextManagementProperties properties = new ContextManagementProperties();
        properties.setMaxPromptChars(120);
        ContextManagementService service = new ContextManagementService(properties);
        List<Message> messages = List.of(
                new SystemMessage("system prompt"),
                new SystemMessage("【历史对话摘要】早期关键上下文"),
                new UserMessage("old user " + "x".repeat(80)),
                new AssistantMessage("old assistant " + "y".repeat(80)),
                new UserMessage("latest user question")
        );

        List<Message> managed = service.applyPromptBudget(messages);
        String joined = managed.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);

        assertThat(joined).contains("system prompt");
        assertThat(joined).contains("【历史对话摘要】早期关键上下文");
        assertThat(joined).contains("latest user question");
        assertThat(joined).contains("早期上下文已省略");
        assertThat(joined).doesNotContain("old user");
        assertThat(joined).doesNotContain("old assistant");
    }

    private ChatMessageDTO message(String id, ChatMessageDTO.RoleType role, String content, int minutes) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.of(2026, 1, 1, 10, minutes))
                .build();
    }
}
