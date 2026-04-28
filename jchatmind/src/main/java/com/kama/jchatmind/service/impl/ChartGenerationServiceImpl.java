package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.chart.ChartArtifact;
import com.kama.jchatmind.model.chart.ChartDataPoint;
import com.kama.jchatmind.model.chart.ChartSeries;
import com.kama.jchatmind.model.chart.ChartSpec;
import com.kama.jchatmind.service.ChartGenerationService;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ChartGenerationServiceImpl implements ChartGenerationService {

    private static final int MAX_DATA_POINTS = 500;
    private static final List<String> SUPPORTED_TYPES = List.of("line", "bar", "pie", "scatter");
    private static final List<String> UNSAFE_TOKENS = List.of(
            "<script", "</script", "javascript:", "function(", "function ", "=>", "data:text/html"
    );

    @Override
    public ChartArtifact generate(ChartSpec spec) {
        ChartSpec normalized = normalizeAndValidate(spec);
        Map<String, Object> option = buildOption(normalized);
        return ChartArtifact.builder()
                .id(UUID.randomUUID().toString())
                .spec(normalized)
                .echartsOption(option)
                .summary(buildSummary(normalized))
                .build();
    }

    private ChartSpec normalizeAndValidate(ChartSpec spec) {
        Assert.notNull(spec, "ChartSpec cannot be null");
        String type = safeRequired(spec.getType(), "Chart type").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported chart type: " + spec.getType());
        }
        validateSafeText(spec.getTitle(), "title");
        validateSafeText(spec.getXAxisName(), "xAxisName");
        validateSafeText(spec.getYAxisName(), "yAxisName");
        validateSafeText(spec.getUnit(), "unit");
        validateSafeText(spec.getDescription(), "description");
        validateCategories(spec.getCategories());

        List<ChartSeries> series = spec.getSeries();
        if (series == null || series.isEmpty()) {
            throw new IllegalArgumentException("Chart series cannot be empty");
        }
        int totalPoints = 0;
        for (ChartSeries item : series) {
            validateSeries(type, item);
            totalPoints += item.getData().size();
        }
        if (totalPoints > MAX_DATA_POINTS) {
            throw new IllegalArgumentException("Chart data points exceed max limit: " + MAX_DATA_POINTS);
        }

        if (("line".equals(type) || "bar".equals(type)) && spec.getCategories() != null && !spec.getCategories().isEmpty()) {
            for (ChartSeries item : series) {
                if (item.getData().size() != spec.getCategories().size()) {
                    throw new IllegalArgumentException("Category count must match each series data count");
                }
            }
        }

        spec.setType(type);
        return spec;
    }

    private void validateSeries(String type, ChartSeries series) {
        Assert.notNull(series, "Chart series item cannot be null");
        validateSafeText(series.getName(), "series.name");
        if (series.getData() == null || series.getData().isEmpty()) {
            throw new IllegalArgumentException("Series data cannot be empty");
        }
        for (ChartDataPoint point : series.getData()) {
            validatePoint(type, point);
        }
    }

    private void validatePoint(String type, ChartDataPoint point) {
        Assert.notNull(point, "Chart data point cannot be null");
        validateSafeText(point.getName(), "data.name");
        switch (type) {
            case "line", "bar", "pie" -> requireFinite(point.getValue(), "data.value");
            case "scatter" -> {
                requireFinite(point.getX(), "data.x");
                requireFinite(point.getY(), "data.y");
            }
            default -> throw new IllegalArgumentException("Unsupported chart type: " + type);
        }
    }

    private void validateCategories(List<String> categories) {
        if (categories == null) {
            return;
        }
        for (String category : categories) {
            validateSafeText(category, "category");
        }
    }

    private Map<String, Object> buildOption(ChartSpec spec) {
        return switch (spec.getType()) {
            case "pie" -> buildPieOption(spec);
            case "scatter" -> buildScatterOption(spec);
            default -> buildAxisOption(spec);
        };
    }

    private Map<String, Object> buildAxisOption(ChartSpec spec) {
        Map<String, Object> option = baseOption(spec, "axis");
        option.put("xAxis", Map.of(
                "type", "category",
                "name", nullToEmpty(spec.getXAxisName()),
                "data", resolveCategories(spec)
        ));
        option.put("yAxis", Map.of(
                "type", "value",
                "name", axisNameWithUnit(spec.getYAxisName(), spec.getUnit())
        ));
        List<Map<String, Object>> series = new ArrayList<>();
        for (ChartSeries item : spec.getSeries()) {
            Map<String, Object> seriesOption = new LinkedHashMap<>();
            seriesOption.put("name", defaultSeriesName(item));
            seriesOption.put("type", spec.getType());
            seriesOption.put("data", item.getData().stream().map(ChartDataPoint::getValue).toList());
            if ("line".equals(spec.getType()) && Boolean.TRUE.equals(spec.getSmooth())) {
                seriesOption.put("smooth", true);
            }
            series.add(seriesOption);
        }
        option.put("series", series);
        return option;
    }

    private Map<String, Object> buildPieOption(ChartSpec spec) {
        Map<String, Object> option = baseOption(spec, "item");
        ChartSeries item = spec.getSeries().get(0);
        List<Map<String, Object>> data = item.getData()
                .stream()
                .map(point -> {
                    Map<String, Object> pointOption = new LinkedHashMap<>();
                    pointOption.put("name", StringUtils.hasText(point.getName()) ? point.getName() : "");
                    pointOption.put("value", point.getValue());
                    return pointOption;
                })
                .toList();
        option.put("series", List.of(Map.of(
                "name", defaultSeriesName(item),
                "type", "pie",
                "radius", "60%",
                "data", data
        )));
        return option;
    }

    private Map<String, Object> buildScatterOption(ChartSpec spec) {
        Map<String, Object> option = baseOption(spec, "axis");
        option.put("xAxis", Map.of(
                "type", "value",
                "name", nullToEmpty(spec.getXAxisName())
        ));
        option.put("yAxis", Map.of(
                "type", "value",
                "name", axisNameWithUnit(spec.getYAxisName(), spec.getUnit())
        ));
        List<Map<String, Object>> series = new ArrayList<>();
        for (ChartSeries item : spec.getSeries()) {
            series.add(Map.of(
                    "name", defaultSeriesName(item),
                    "type", "scatter",
                    "data", item.getData().stream()
                            .map(point -> List.of(point.getX(), point.getY()))
                            .toList()
            ));
        }
        option.put("series", series);
        return option;
    }

    private Map<String, Object> baseOption(ChartSpec spec, String tooltipTrigger) {
        Map<String, Object> option = new LinkedHashMap<>();
        if (StringUtils.hasText(spec.getTitle())) {
            option.put("title", Map.of("text", spec.getTitle()));
        }
        option.put("tooltip", Map.of("trigger", tooltipTrigger));
        option.put("legend", Map.of("type", "scroll"));
        return option;
    }

    private List<String> resolveCategories(ChartSpec spec) {
        if (spec.getCategories() != null && !spec.getCategories().isEmpty()) {
            return spec.getCategories();
        }
        return spec.getSeries().get(0).getData()
                .stream()
                .map(point -> StringUtils.hasText(point.getName()) ? point.getName() : "")
                .toList();
    }

    private String buildSummary(ChartSpec spec) {
        int points = spec.getSeries().stream().mapToInt(item -> item.getData().size()).sum();
        String title = StringUtils.hasText(spec.getTitle()) ? spec.getTitle() : "Untitled chart";
        return "%s generated as %s chart with %d series and %d data points."
                .formatted(title, spec.getType(), spec.getSeries().size(), points);
    }

    private String safeRequired(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        validateSafeText(value, fieldName);
        return value.trim();
    }

    private void validateSafeText(String value, String fieldName) {
        if (value == null) {
            return;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String token : UNSAFE_TOKENS) {
            if (lower.contains(token)) {
                throw new IllegalArgumentException("Unsafe chart text in " + fieldName);
            }
        }
        if (value.length() > 500) {
            throw new IllegalArgumentException("Chart text too long in " + fieldName);
        }
    }

    private void requireFinite(Double value, String fieldName) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException(fieldName + " must be a finite number");
        }
    }

    private String axisNameWithUnit(String axisName, String unit) {
        String name = nullToEmpty(axisName);
        if (!StringUtils.hasText(unit)) {
            return name;
        }
        return StringUtils.hasText(name) ? name + " (" + unit + ")" : unit;
    }

    private String defaultSeriesName(ChartSeries series) {
        return StringUtils.hasText(series.getName()) ? series.getName() : "series";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
