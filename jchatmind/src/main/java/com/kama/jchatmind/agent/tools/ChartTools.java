package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.search.AgenticSearchContext;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.chart.ChartSpec;
import com.kama.jchatmind.service.ChartGenerationService;
import com.kama.jchatmind.service.SseService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChartTools implements Tool {

    public static final String TOOL_NAME = "generateChart";

    private final ChartGenerationService chartGenerationService;
    private final SseService sseService;
    private final ObjectMapper objectMapper;

    public ChartTools(ChartGenerationService chartGenerationService,
                      SseService sseService,
                      ObjectMapper objectMapper) {
        this.chartGenerationService = chartGenerationService;
        this.sseService = sseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Generate a safe ECharts JSON option from structured chart data and push it to the frontend when possible.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = TOOL_NAME,
            description = """
                    Generate a chart for the user from structured data in the conversation.
                    Return only safe ECharts JSON configuration, never executable JavaScript, HTML, script tags, or external resources.
                    Supported chart types: line, bar, pie, scatter. Use line with smooth=true for a curved line chart.
                    ChartSpec fields: type, title, xAxisName, yAxisName, unit, description, smooth, categories, series.
                    For line/bar/pie, each data point must include name and value. For scatter, each data point must include x and y.
                    Only call this tool when the user asked for a visualization or a chart would clearly improve the answer.
                    If required data is missing or ambiguous, ask the user for the missing data instead of inventing values.
                    """
    )
    public String generateChart(ChartSpec spec) {
        try {
            ChartArtifact artifact = chartGenerationService.generate(spec);
            emitChart(artifact);
            return objectMapper.writeValueAsString(artifact);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "ERROR",
                        "tool", TOOL_NAME,
                        "message", e.getMessage() == null ? "Chart generation failed" : e.getMessage()
                ));
            } catch (JsonProcessingException jsonException) {
                return "{\"status\":\"ERROR\",\"tool\":\"generateChart\",\"message\":\"Chart generation failed\"}";
            }
        }
    }

    private void emitChart(ChartArtifact artifact) {
        AgenticSearchContext.Context context = AgenticSearchContext.get();
        if (context == null || context.chatSessionId() == null) {
            return;
        }
        try {
            sseService.send(context.chatSessionId(), SseMessage.builder()
                    .type(SseMessage.Type.CHART_GENERATED)
                    .payload(SseMessage.Payload.builder()
                            .chart(artifact)
                            .done(true)
                            .build())
                    .build());
        } catch (Exception ignored) {
            // Chart generation should still succeed when the browser has no active SSE connection.
        }
    }
}
