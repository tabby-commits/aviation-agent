package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.CorpusImportResponse;
import com.kama.jchatmind.model.response.GetPaperResponse;
import com.kama.jchatmind.model.response.GetPapersResponse;
import com.kama.jchatmind.model.response.MembershipImportResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.model.response.PaperImportStatsResponse;
import com.kama.jchatmind.model.response.PaperScreeningImportResponse;
import com.kama.jchatmind.service.PaperCorpusService;
import com.kama.jchatmind.service.PaperFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 论文元数据接口（导入/查询/统计，SPEC 第 2 节）
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class PaperController {

    private final PaperFacadeService paperFacadeService;

    private final PaperCorpusService paperCorpusService;

    // 导入论文元数据（幂等，source=WOS/CNKI）
    @PostMapping("/papers/import/metadata")
    public ApiResponse<PaperImportResponse> importMetadata(
            @RequestParam("file") MultipartFile file,
            @RequestParam("source") String source) {
        return ApiResponse.success(paperFacadeService.importMetadata(file, source));
    }

    // 导入筛选结论（document_screen.csv，更新国别判定与纳入状态）
    @PostMapping("/papers/import/screening")
    public ApiResponse<PaperScreeningImportResponse> importScreening(
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(paperFacadeService.importScreening(file));
    }

    // 导入论文分类归属（paper_members.csv，EASC 一级类目映射为新体系 code）
    @PostMapping("/papers/import/taxonomy-memberships")
    public ApiResponse<MembershipImportResponse> importTaxonomyMemberships(
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(paperFacadeService.importTaxonomyMemberships(file));
    }

    // 分页查询论文
    @GetMapping("/papers")
    public ApiResponse<GetPapersResponse> getPapers(
            @RequestParam(required = false) String sourceDb,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) Integer yearTo,
            @RequestParam(required = false) String screeningStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setSourceDb(sourceDb);
        query.setCountry(country);
        query.setYearFrom(yearFrom);
        query.setYearTo(yearTo);
        query.setScreeningStatus(screeningStatus);
        query.setKeyword(keyword);
        query.setPage(page);
        query.setPageSize(Math.min(pageSize, 200));
        return ApiResponse.success(paperFacadeService.getPapers(query));
    }

    // 论文详情
    @GetMapping("/papers/{docId}")
    public ApiResponse<GetPaperResponse> getPaper(@PathVariable String docId) {
        return ApiResponse.success(paperFacadeService.getPaper(docId));
    }

    // 入库统计
    @GetMapping("/papers/import/stats")
    public ApiResponse<PaperImportStatsResponse> getImportStats() {
        return ApiResponse.success(paperFacadeService.getImportStats());
    }

    // 批量导入论文全文到 RAG（同步分批，幂等；循环调用直至 remaining=0）
    @PostMapping("/papers/import/corpus")
    public ApiResponse<CorpusImportResponse> importCorpus(
            @RequestParam("pdfDir") String pdfDir,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(paperCorpusService.importCorpus(pdfDir, limit));
    }
}
