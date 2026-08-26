package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.request.ParameterQueryRequest;
import com.kama.jchatmind.model.response.GetParametersResponse;
import com.kama.jchatmind.model.response.ParameterEvidenceSummary;
import com.kama.jchatmind.service.ParameterFacadeService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 参数证据查询工具（指标（13）已公开最高技术指标、（14）公开技术性能变化）
 */
@Component
public class ParameterEvidenceTool implements Tool {

    private final ParameterFacadeService parameterFacadeService;

    public ParameterEvidenceTool(ParameterFacadeService parameterFacadeService) {
        this.parameterFacadeService = parameterFacadeService;
    }

    @Override
    public String getName() {
        return "ParameterEvidenceTool";
    }

    @Override
    public String getDescription() {
        return "查询人工审核的性能参数证据（参数族/技术对象/国别/关键词过滤），"
                + "返回参数取值、单位、测试条件、证据原文、页码与来源论文，每条数值可回查原文。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "ParameterEvidenceTool",
            description = "查询参数证据表：参数族（如 latency and delay、throughput and data rate，原文为英文族名）、"
                    + "国别 country（CN/US）、关键词（技术对象/参数名/证据原文）、docId（限定某论文），"
                    + "page/pageSize 可选。返回：技术对象、参数名、数值区间、单位、测试条件、证据原文、页码、论文 docId。"
                    + "用于指标（13）已公开最高技术指标与（14）性能变化分析；引用时必须给出论文 docId 与页码。"
    )
    public String queryEvidence(@Nullable String family,
                                @Nullable String country,
                                @Nullable String keyword,
                                @Nullable String docId,
                                @Nullable Integer page,
                                @Nullable Integer pageSize) {
        ParameterQueryRequest query = new ParameterQueryRequest();
        query.setFamily(family);
        query.setCountry(country);
        query.setKeyword(keyword);
        query.setDocId(docId);
        query.setPage(page == null || page < 1 ? 1 : page);
        query.setPageSize(pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 30));

        GetParametersResponse response = parameterFacadeService.getParameters(query);
        String items = response.getParameters().stream()
                .map(this::format)
                .collect(Collectors.joining("\n"));
        return "共 " + response.getTotal() + " 条参数证据（第 " + response.getPage() + " 页）：\n"
                + (items.isEmpty() ? "（无结果；参数族为英文，如 latency and delay）" : items);
    }

    private String format(ParameterEvidenceSummary p) {
        String value = p.getValueRaw() != null ? p.getValueRaw()
                : (p.getValueMin() != null ? String.valueOf(p.getValueMin()) : "?")
                  + (p.getValueMax() != null ? "~" + p.getValueMax() : "");
        return "- [%s] %s | %s = %s %s | 条件: %s | %s 第%s页 | %s (%s, %d年)".formatted(
                p.getFirstAuthorCountry() == null ? "?" : p.getFirstAuthorCountry(),
                p.getDecisionId(),
                p.getTechnicalObject() == null ? "?" : truncate(p.getTechnicalObject(), 30),
                value,
                p.getUnitNormalized() == null ? "" : p.getUnitNormalized(),
                truncate(p.getConditionText(), 40),
                "证据: " + truncate(p.getEvidenceText(), 60),
                p.getPageNumber() == null ? "?" : p.getPageNumber(),
                p.getDocId(),
                truncate(p.getTitle(), 40),
                p.getPublishYear() == null ? 0 : p.getPublishYear());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
