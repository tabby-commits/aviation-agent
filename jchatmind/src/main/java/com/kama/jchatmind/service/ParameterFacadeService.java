package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.ParameterQueryRequest;
import com.kama.jchatmind.model.response.GetParametersResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 参数证据门面服务
 */
public interface ParameterFacadeService {

    /**
     * 导入参数证据（confirmed_parameters_final.csv，按 decision_id 幂等 upsert）
     * 导入完成后自动从 paper 表回填 publish_year
     */
    PaperImportResponse importParameters(MultipartFile file);

    /** 分页条件查询参数证据（参数族/国别/关键词/论文） */
    GetParametersResponse getParameters(ParameterQueryRequest query);
}
