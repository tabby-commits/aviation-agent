package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.service.BibliometricFacadeService;
import com.kama.jchatmind.service.bibliometric.BibliometricResult;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文献计量计算工具（指标（9）—（12）（16），公式与中期报告 3.2.3 节一致）
 */
@Component
public class BibliometricTool implements Tool {

    private final BibliometricFacadeService bibliometricFacadeService;

    public BibliometricTool(BibliometricFacadeService bibliometricFacadeService) {
        this.bibliometricFacadeService = bibliometricFacadeService;
    }

    @Override
    public String getName() {
        return "BibliometricTool";
    }

    @Override
    public String getDescription() {
        return "基于有效研究论文计算文献计量指标：完全/分数计数论文量、高被引数量与占比（前10%含并列分数分配）、"
                + "年度趋势与三年移动平均与CAGR、国际合作率、机构贡献份额与HHI。按公式计算，不要自行估算。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "BibliometricTool",
            description = "文献计量计算（输入年份区间与可选分类 code）：yearFrom/yearTo 必填；"
                    + "taxonomyCode 可选（限定某技术类别，取值见 TaxonomyBrowseTool）。"
                    + "基于 included 有效研究论文，输出各国的：完全计数N_F、分数计数N_W、高被引数量HC（前10%，"
                    + "参照组=同年份同文献类型，并列分数分配）、高被引占比HCR、年度序列、三年移动平均、CAGR、"
                    + "国际合作率ICR、机构份额Top与HHI及有效机构数。覆盖指标（9）（10）（11）（12）（16）。"
    )
    public String analyze(int yearFrom, int yearTo, @Nullable String taxonomyCode) {
        BibliometricResult result = bibliometricFacadeService.analyze(yearFrom, yearTo, taxonomyCode);

        String countries = result.getByCountry().values().stream()
                .map(this::formatCountry)
                .collect(Collectors.joining("\n\n"));
        return "文献计量（%d-%d 年，共 %d 篇有效研究论文%s）：\n\n%s".formatted(
                result.getYearFrom(),
                result.getYearTo(),
                result.getTotalPapers(),
                taxonomyCode == null || taxonomyCode.isBlank() ? "" : "，分类=" + taxonomyCode,
                countries.isEmpty() ? "（该区间无有效研究论文）" : countries);
    }

    private String formatCountry(BibliometricResult.CountryStats c) {
        String years = c.getYearSeries().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
        String ma = c.getMovingAverage3().entrySet().stream()
                .map(e -> e.getKey() + ":" + String.format("%.1f", e.getValue()))
                .collect(Collectors.joining(", "));
        String institutions = c.getInstitutionShares().stream()
                .limit(5)
                .map(s -> "%s(%.1f%%)" .formatted(s.getInstitution(), s.getShare() * 100))
                .collect(Collectors.joining(", "));
        return """
                [%s] 完全计数 N_F=%d，分数计数 N_W=%.2f
                高被引 HC=%.2f（HCR=%.1f%%，前10%%分数分配）
                国际合作率 ICR=%.1f%%（%d 篇国际合作）
                年度序列：%s；三年移动平均：%s；CAGR=%s
                机构Top5：%s；HHI=%.4f（有效机构数 %.2f）""".formatted(
                c.getCountry(), c.getFullCount(), c.getFractionalCount(),
                c.getHighCitedCount(), c.getHighCitedRatio(),
                c.getInternationalRatio(), c.getInternationalCount(),
                years, ma,
                c.getCagr() == null ? "N/A" : String.format("%.1f%%", c.getCagr()),
                institutions, c.getInstitutionHhi(), c.getEffectiveInstitutions());
    }
}
