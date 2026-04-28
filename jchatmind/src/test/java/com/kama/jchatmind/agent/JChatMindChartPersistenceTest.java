package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.ChartTools;
import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.chart.ChartDataPoint;
import com.kama.jchatmind.model.chart.ChartSeries;
import com.kama.jchatmind.model.chart.ChartSpec;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindChartPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateChartToolResponsePersistsChartArtifactMetadata() throws Exception {
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.createChatMessage(org.mockito.ArgumentMatchers.any(ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("msg-1").build());
        JChatMind runtime = new JChatMind(
                "agent-1",
                "agent",
                "description",
                null,
                "deepseek-chat",
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
                objectMapper
        );
        ChartArtifact artifact = ChartArtifact.builder()
                .id("chart-1")
                .spec(ChartSpec.builder()
                        .type("line")
                        .series(List.of(ChartSeries.builder()
                                .data(List.of(ChartDataPoint.builder().name("A").value(1.0).build()))
                                .build()))
                        .build())
                .echartsOption(Map.of("series", List.of(Map.of("type", "line"))))
                .summary("chart")
                .build();
        ToolResponseMessage message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", ChartTools.TOOL_NAME, objectMapper.writeValueAsString(artifact))))
                .build();

        Method saveMessage = JChatMind.class.getDeclaredMethod("saveMessage", org.springframework.ai.chat.messages.Message.class);
        saveMessage.setAccessible(true);
        saveMessage.invoke(runtime, message);

        ArgumentCaptor<ChatMessageDTO> captor = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(captor.capture());
        ChatMessageDTO saved = captor.getValue();
        assertThat(saved.getMetadata().getChartArtifacts()).hasSize(1);
        assertThat(saved.getMetadata().getChartArtifacts().get(0).getId()).isEqualTo("chart-1");
    }
}
