package com.kama.jchatmind.service;

import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.chart.ChartSpec;

public interface ChartGenerationService {
    ChartArtifact generate(ChartSpec spec);
}
