package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ContextManagementProperties;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.impl.ContextManagementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindContextManagementTest {

    @Test
    void saveMessagePersistsCompressedToolResponseMetadata() throws Exception {
        ContextManagementProperties properties = new ContextManagementProperties();
        properties.setToolResultMaxChars(20);
        properties.setToolResultHeadChars(8);
        properties.setToolResultTailChars(8);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("msg-1").build());
        JChatMind runtime = new JChatMind(
                "agent-1",
                "agent",
                "description",
                null,
                "deepseek",
                null,
                20,
                List.of(),
                List.of(),
                List.of(),
                "session-1",
                null,
                chatMessageFacadeService,
                null,
                AgentRole.MAIN,
                true,
                false,
                1,
                new ObjectMapper(),
                new ContextManagementService(properties)
        );
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "readFile",
                        "HEAD-1234567890-MIDDLE-abcdefghij-TAIL"
                )))
                .build();

        Method saveMessage = JChatMind.class.getDeclaredMethod("saveMessage", Message.class);
        saveMessage.setAccessible(true);
        saveMessage.invoke(runtime, message);

        ArgumentCaptor<ChatMessageDTO> captor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(captor.capture());
        ChatMessageDTO saved = captor.getValue();
        assertThat(saved.getContent()).contains("HEAD-123");
        assertThat(saved.getContent()).doesNotContain("MIDDLE");
        assertThat(saved.getMetadata().getToolResponse().responseData()).isEqualTo(saved.getContent());
        assertThat(saved.getMetadata().getContextManagement().getCompressionType()).isEqualTo("TOOL_RESULT_TRUNCATED");
    }
}
