package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.response.TaxonomyNodeResponse;
import com.kama.jchatmind.model.response.TaxonomyPapersResponse;
import com.kama.jchatmind.service.TaxonomyFacadeService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 技术层级（分类体系）浏览工具
 */
@Component
public class TaxonomyBrowseTool implements Tool {

    private final TaxonomyFacadeService taxonomyFacadeService;

    public TaxonomyBrowseTool(TaxonomyFacadeService taxonomyFacadeService) {
        this.taxonomyFacadeService = taxonomyFacadeService;
    }

    @Override
    public String getName() {
        return "TaxonomyBrowseTool";
    }

    @Override
    public String getDescription() {
        return "浏览低轨卫星星座技术分类体系（1 层 6 类）：查看全部类别与论文数，"
                + "或查询某类别下的论文列表与中美分布，用于按技术层级组织竞争力分析。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "TaxonomyBrowseTool",
            description = "浏览技术分类体系：code 为空时返回 6 个类别（code/中文名/说明/论文数）；"
                    + "code 非空（如 space-computing、optical-isl、inter-satellite-networking、"
                    + "sat-ground-integration、interference-mitigation、constellation-design）时返回该类别的"
                    + "中美论文分布与论文列表（limit 默认 10）。用于按技术层级组织证据与选择分析范围。"
    )
    public String browse(@Nullable String code, @Nullable Integer limit) {
        if (code == null || code.isBlank()) {
            String tree = taxonomyFacadeService.getTaxonomyTree().stream()
                    .map(n -> "- %s %s（%s）：论文 %d 篇".formatted(n.getCode(), n.getNameCn(), n.getNameEn(), n.getPaperCount()))
                    .collect(Collectors.joining("\n"));
            return "低轨卫星星座技术分类体系（1 层 6 类）：\n" + tree;
        }

        TaxonomyPapersResponse resp = taxonomyFacadeService.getTaxonomyPapers(
                code.trim(), limit == null || limit < 1 ? 10 : Math.min(limit, 30));
        String dist = resp.getByCountry().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        String papers = resp.getPapers().stream()
                .map(p -> "- %s | %s | %s | %d年".formatted(
                        p.getDocId(),
                        p.getCountry() == null ? "?" : p.getCountry(),
                        truncate(p.getTitle(), 70),
                        p.getPublishYear() == null ? 0 : p.getPublishYear()))
                .collect(Collectors.joining("\n"));
        return "分类 %s（%s）共 %d 篇，国别分布：%s\n论文列表：\n%s".formatted(
                resp.getCode(), resp.getNameCn(), resp.getTotal(), dist, papers);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
