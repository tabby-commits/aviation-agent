package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.GetPaperResponse;
import com.kama.jchatmind.model.response.GetPapersResponse;
import com.kama.jchatmind.model.response.MembershipImportResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.model.response.PaperImportStatsResponse;
import com.kama.jchatmind.model.response.PaperScreeningImportResponse;
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

    /**
     * 导入筛选结论（document_screen.csv）：更新 screening_status/国别判定/文件名/排除原因
     * paper 表中不存在的 doc_id 跳过并计数
     */
    PaperScreeningImportResponse importScreening(MultipartFile file);

    /**
     * 导入论文分类归属（paper_members.csv）：
     * 只处理 level2_assignment，EASC 一级类目按内置映射转为新体系 code；
     * candidate_cluster / 未匹配类目 / paper 表缺失的行分别计数跳过
     */
    MembershipImportResponse importTaxonomyMemberships(MultipartFile file);

    /** 分页条件查询论文 */
    GetPapersResponse getPapers(PaperQueryRequest query);

    /** 论文详情（不存在抛 BizException） */
    GetPaperResponse getPaper(String docId);

    /** 入库统计（按来源库与筛选状态分组） */
    PaperImportStatsResponse getImportStats();
}
