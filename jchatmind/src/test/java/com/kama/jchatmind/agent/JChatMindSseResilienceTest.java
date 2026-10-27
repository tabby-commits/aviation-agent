package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JChatMindSseResilienceTest {

    @Test
    void missingBrowserSseConnectionMustNotAbortAgentProgress() throws Exception {
        SseService sseService = mock(SseService.class);
        doThrow(new RuntimeException("No client found for chatSessionId: session-1"))
                .when(sseService).send(any(), any());
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.createChatMessage(any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("msg-1").build());
        ChatMessageConverter converter = mock(ChatMessageConverter.class);

        JChatMind runtime = new JChatMind(
                "agent-1", "agent", "description", null, "deepseek", null, 20,
                List.of(), List.of(), List.of(), "session-1", sseService,
                messageService, converter, AgentRole.MAIN, true, true, 1,
                new ObjectMapper()
        );
        Method save = JChatMind.class.getDeclaredMethod(
                "saveMessage", org.springframework.ai.chat.messages.Message.class);
        save.setAccessible(true);
        save.invoke(runtime, new AssistantMessage("working"));

        Method refresh = JChatMind.class.getDeclaredMethod("refreshPendingMessages");
        refresh.setAccessible(true);

        assertDoesNotThrow(() -> refresh.invoke(runtime));
    }
}
