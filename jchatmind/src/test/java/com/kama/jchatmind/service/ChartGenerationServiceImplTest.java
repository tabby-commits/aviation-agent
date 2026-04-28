package com.kama.jchatmind.service;

import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.chart.ChartDataPoint;
import com.kama.jchatmind.model.chart.ChartSeries;
import com.kama.jchatmind.model.chart.ChartSpec;
import com.kama.jchatmind.service.impl.ChartGenerationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartGenerationServiceImplTest {

    private final ChartGenerationService service = new ChartGenerationServiceImpl();

    @Test
    void lineChartBuildsSafeEchartsOption() {
        ChartArtifact artifact = service.generate(ChartSpec.builder()
                .type("line")
                .title("Revenue Trend")
                .xAxisName("Month")
                .yAxisName("Revenue")
                .unit("USD")
                .smooth(true)
                .categories(List.of("Jan", "Feb"))
                .series(List.of(ChartSeries.builder()
                        .name("2026")
                        .data(List.of(
                                ChartDataPoint.builder().name("Jan").value(10.0).build(),
                                ChartDataPoint.builder().name("Feb").value(15.0).build()
                        ))
                        .build()))
                .build());

        assertThat(artifact.getId()).isNotBlank();
        assertThat(artifact.getSummary()).contains("line chart");
        assertThat(artifact.getEchartsOption()).containsKeys("title", "tooltip", "legend", "xAxis", "yAxis", "series");
        List<Map<String, Object>> series = (List<Map<String, Object>>) artifact.getEchartsOption().get("series");
        assertThat(series.get(0)).containsEntry("type", "line").containsEntry("smooth", true);
        assertThat(series.get(0).get("data")).isEqualTo(List.of(10.0, 15.0));
    }

    @Test
    void supportsBarPieAndScatter() {
        assertThat(service.generate(axisSpec("bar")).getEchartsOption().get("series").toString()).contains("bar");
        assertThat(service.generate(pieSpec()).getEchartsOption().get("series").toString()).contains("pie");
        assertThat(service.generate(scatterSpec()).getEchartsOption().get("series").toString()).contains("scatter");
    }

    @Test
    void rejectsInvalidTypeAndMismatchedCategories() {
        assertThatThrownBy(() -> service.generate(axisSpec("radar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported chart type");

        ChartSpec spec = axisSpec("line");
        spec.setCategories(List.of("Only one"));
        assertThatThrownBy(() -> service.generate(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Category count");
    }

    @Test
    void rejectsUnsafeScriptLikeText() {
        ChartSpec spec = axisSpec("line");
        spec.setTitle("function() { alert(1) }");

        assertThatThrownBy(() -> service.generate(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe chart text");
    }

    private ChartSpec axisSpec(String type) {
        return ChartSpec.builder()
                .type(type)
                .title("Axis Chart")
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

    private ChartSpec pieSpec() {
        return ChartSpec.builder()
                .type("pie")
                .title("Pie Chart")
                .series(List.of(ChartSeries.builder()
                        .name("Share")
                        .data(List.of(
                                ChartDataPoint.builder().name("A").value(40.0).build(),
                                ChartDataPoint.builder().name("B").value(60.0).build()
                        ))
                        .build()))
                .build();
    }

    private ChartSpec scatterSpec() {
        return ChartSpec.builder()
                .type("scatter")
                .title("Scatter Chart")
                .series(List.of(ChartSeries.builder()
                        .name("Samples")
                        .data(List.of(
                                ChartDataPoint.builder().x(1.0).y(2.0).build(),
                                ChartDataPoint.builder().x(2.0).y(4.0).build()
                        ))
                        .build()))
                .build();
    }
}
