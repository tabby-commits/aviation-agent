package com.kama.jchatmind.model.chart;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartSpec {
    private String type;
    private String title;
    private String xAxisName;
    private String yAxisName;
    private String unit;
    private String description;
    private Boolean smooth;
    private List<String> categories;
    private List<ChartSeries> series;
}
