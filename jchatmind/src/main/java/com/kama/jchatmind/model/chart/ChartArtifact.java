package com.kama.jchatmind.model.chart;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartArtifact {
    private String id;
    private ChartSpec spec;
    private Map<String, Object> echartsOption;
    private String summary;
}
