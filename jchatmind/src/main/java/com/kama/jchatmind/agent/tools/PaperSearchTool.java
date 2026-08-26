package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.GetPapersResponse;
import com.kama.jchatmind.model.response.PaperSummary;
import com.kama.jchatmind.service.PaperFacadeService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 论文检索工具（指标（9）（11）（12）（16）证据汇集）
 */
@Component
public class PaperSearchTool implements Tool {

    private final PaperFacadeService paperFacadeService;

    public PaperSearchTool(PaperFacadeService paperFacadeService) {
        this.paperFacadeService = paperFacadeService;
    }

    @Override
    public String getName() {
        return "PaperSearchTool";
    }

    @Override
    public String getDescription() {
        return "按年份区间、国别（CN/US）、筛选状态（included=有效研究论文）、来源库（WOS/CNKI）、关键词过滤检索论文元数据，"
                + "返回结构化论文列表（docId、标题、第一作者、国别、年份、期刊、被引）。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "PaperSearchTool",
            description = "检索论文元数据：参数 keyword（标题/摘要关键词，可选）、country（CN/US，可选）、"
                    + "yearFrom/yearTo（年份区间，可选）、screeningStatus（included=有效研究论文，可选）、"
                    + "sourceDb（WOS/CNKI，可选）、page（默认1）、pageSize（默认20）。返回论文列表与总数。"
                    + "用于竞争力指标（9）核心论文数量、（11）研究热度、（12）国际合作、（16）机构贡献的证据汇集。"
    )
    public String paperSearch(@Nullable String keyword,
                              @Nullable String country,
                              @Nullable Integer yearFrom,
                              @Nullable Integer yearTo,
                              @Nullable String screeningStatus,
                              @Nullable String sourceDb,
                              @Nullable Integer page,
                              @Nullable Integer pageSize) {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setKeyword(keyword);
        query.setCountry(country);
        query.setYearFrom(yearFrom);
        query.setYearTo(yearTo);
        query.setScreeningStatus(screeningStatus);
        query.setSourceDb(sourceDb);
        query.setPage(page == null || page < 1 ? 1 : page);
        query.setPageSize(pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50));

        GetPapersResponse response = paperFacadeService.getPapers(query);
        String items = response.getPapers().stream()
                .map(this::format)
                .collect(Collectors.joining("\n"));
        return "共 " + response.getTotal() + " 篇（第 " + response.getPage() + " 页，每页 " + response.getPageSize() + "）：\n"
                + (items.isEmpty() ? "（无结果，可调整过滤条件）" : items);
    }

    private String format(PaperSummary p) {
        return "- %s | %s | %s | %d年 | 被引%d | %s".formatted(
                p.getDocId(),
                p.getFirstAuthorCountry() == null ? "?" : p.getFirstAuthorCountry(),
                truncate(p.getTitle(), 80),
                p.getPublishYear() == null ? 0 : p.getPublishYear(),
                p.getCitedCount() == null ? 0 : p.getCitedCount(),
                p.getScreeningStatus());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
