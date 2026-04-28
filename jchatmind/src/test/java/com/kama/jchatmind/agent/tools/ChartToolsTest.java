package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.search.AgenticSearchContext;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.chart.ChartDataPoint;
import com.kama.jchatmind.model.chart.ChartSeries;
import com.kama.jchatmind.model.chart.ChartSpec;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ChartGenerationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChartToolsTest {

    private final SseService sseService = mock(SseService.class);
    private final ChartTools tool = new ChartTools(new ChartGenerationServiceImpl(), sseService, new ObjectMapper());

    @AfterEach
    void tearDown() {
        AgenticSearchContext.clear();
    }

    @Test
    void generateChartReturnsArtifactAndEmitsSseWhenSessionExists() {
        AgenticSearchContext.set("session-1", "deepseek-chat");

        String result = tool.generateChart(lineSpec());

        assertThat(result).contains("\"echartsOption\"").contains("\"line\"");
        ArgumentCaptor<SseMessage> messageCaptor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService).send(org.mockito.ArgumentMatchers.eq("session-1"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getType()).isEqualTo(SseMessage.Type.CHART_GENERATED);
        assertThat(messageCaptor.getValue().getPayload().getChart().getEchartsOption()).containsKey("series");
    }

    @Test
    void generateChartDoesNotEmitWhenSessionMissing() {
        String result = tool.generateChart(lineSpec());

        assertThat(result).contains("\"echartsOption\"");
        verify(sseService, never()).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidSpecReturnsStructuredError() {
        ChartSpec spec = lineSpec();
        spec.setType("html");

        String result = tool.generateChart(spec);

        assertThat(result).contains("\"status\":\"ERROR\"").contains("Unsupported chart type");
    }

    private ChartSpec lineSpec() {
        return ChartSpec.builder()
                .type("line")
                .title("Trend")
                .categories(List.of("A", "B"))
                .series(List.of(ChartSeries.builder()
                        .name("S1")
                        .data(List.of(
                                ChartDataPoint.builder().name("A").value(1.0).build(),
                                ChartDataPoint.builder().name("B").value(2.0).build()
                        ))
                        .build()))
                .build();
    }
}
