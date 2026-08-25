package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.ParameterQueryRequest;
import com.kama.jchatmind.model.response.GetParametersResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.service.ParameterFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 参数证据接口（导入按 SPEC 第 2 节挂载在 /api/papers/import/parameters，查询在 /api/parameters）
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ParameterController {

    private final ParameterFacadeService parameterFacadeService;

    // 导入参数证据（confirmed_parameters_final.csv，按 decision_id 幂等）
    @PostMapping("/papers/import/parameters")
    public ApiResponse<PaperImportResponse> importParameters(
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(parameterFacadeService.importParameters(file));
    }

    // 分页查询参数证据（过滤：参数族/国别/关键词/论文）
    @GetMapping("/parameters")
    public ApiResponse<GetParametersResponse> getParameters(
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String docId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        ParameterQueryRequest query = new ParameterQueryRequest();
        query.setFamily(family);
        query.setCountry(country);
        query.setKeyword(keyword);
        query.setDocId(docId);
        query.setPage(page);
        query.setPageSize(Math.min(pageSize, 200));
        return ApiResponse.success(parameterFacadeService.getParameters(query));
    }
}
