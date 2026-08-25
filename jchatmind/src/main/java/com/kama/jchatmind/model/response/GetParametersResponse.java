package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 参数证据分页查询响应
 */
@Data
@Builder
public class GetParametersResponse {
    private long total;
    private int page;
    private int pageSize;
    private List<ParameterEvidenceSummary> parameters;
}
