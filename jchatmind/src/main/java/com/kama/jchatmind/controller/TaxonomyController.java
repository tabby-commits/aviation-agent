package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.response.TaxonomyNodeResponse;
import com.kama.jchatmind.service.TaxonomyFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类体系接口（SPEC 第 2 节：taxonomy 查询）
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class TaxonomyController {

    private final TaxonomyFacadeService taxonomyFacadeService;

    // 分类体系全量节点（含各分类论文数）
    @GetMapping("/taxonomy")
    public ApiResponse<List<TaxonomyNodeResponse>> getTaxonomy() {
        return ApiResponse.success(taxonomyFacadeService.getTaxonomyTree());
    }
}
