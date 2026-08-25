package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 论文分页查询响应
 */
@Data
@Builder
public class GetPapersResponse {
    private long total;
    private int page;
    private int pageSize;
    private List<PaperSummary> papers;
}
