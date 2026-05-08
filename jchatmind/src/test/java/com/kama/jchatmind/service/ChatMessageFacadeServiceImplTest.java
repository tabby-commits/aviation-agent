package com.kama.jchatmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatMessageFacadeServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final ChatMessageFacadeService service = new ChatMessageFacadeServiceImpl(
            mapper,
            new ChatMessageConverter(objectMapper),
            mock(ApplicationEventPublisher.class)
    );

    @Test
    void getChatMessagesBySessionIdHidesInternalSummaryMessages() throws Exception {
        ChatMessageDTO.MetaData internalMetadata = ChatMessageDTO.MetaData.builder()
                .contextManagement(ChatMessageDTO.ContextManagement.builder()
                        .compressionType("CONVERSATION_SUMMARY")
                        .internalMessage(true)
                        .build())
                .build();
        ChatMessage summary = ChatMessage.builder()
                .id("summary-1")
                .sessionId("session-1")
                .role("system")
                .content("【历史对话摘要】内部摘要")
                .metadata(objectMapper.writeValueAsString(internalMetadata))
                .createdAt(LocalDateTime.now())
                .build();
        ChatMessage visible = ChatMessage.builder()
                .id("visible-1")
                .sessionId("session-1")
                .role("user")
                .content("用户可见消息")
                .createdAt(LocalDateTime.now())
                .build();
        when(mapper.selectBySessionId("session-1")).thenReturn(List.of(summary, visible));

        assertThat(service.getChatMessagesBySessionId("session-1").getChatMessages())
                .extracting("id")
                .containsExactly("visible-1");
    }
}
