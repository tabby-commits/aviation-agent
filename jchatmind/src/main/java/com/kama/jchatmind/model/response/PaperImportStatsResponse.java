package com.kama.jchatmind.model.response;

import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 论文入库统计（GET /api/papers/import/stats）
 */
@Data
@Builder
public class PaperImportStatsResponse {
    /** key=source_db（WOS/CNKI），value=记录数 */
    private Map<String, Long> bySourceDb;
    /** key=screening_status（pending/included/excluded），value=记录数 */
    private Map<String, Long> byScreeningStatus;
    private long total;
}
