package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.GetPaperResponse;
import com.kama.jchatmind.model.response.GetPapersResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.model.response.PaperImportStatsResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 论文元数据门面服务
 */
public interface PaperFacadeService {

    /**
     * 导入论文元数据（幂等：按 doc_id upsert）
     *
     * @param file   上传的源文件（WoS CSV 或 CNKI RefWorks TXT）
     * @param source WOS / CNKI（大小写不敏感）
     */
    PaperImportResponse importMetadata(MultipartFile file, String source);

    /** 分页条件查询论文 */
    GetPapersResponse getPapers(PaperQueryRequest query);

    /** 论文详情（不存在抛 BizException） */
    GetPaperResponse getPaper(String docId);

    /** 入库统计（按来源库与筛选状态分组） */
    PaperImportStatsResponse getImportStats();
}
