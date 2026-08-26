package com.kama.jchatmind.service.bibliometric;

import java.util.List;

/**
 * 文献计量输入记录（一篇论文的计量所需字段）
 *
 * @param docId               论文唯一标识
 * @param publishYear         发表年份
 * @param docType             文献类型（高被引参照组维度之一）
 * @param citedCount          被引次数
 * @param firstAuthorCountry  第一作者国别（人工判定，权威口径）
 * @param affiliations        机构地址列表（如 "Univ A, Beijing, China"）
 */
public record PaperBiblioRecord(
        String docId,
        Integer publishYear,
        String docType,
        Integer citedCount,
        String firstAuthorCountry,
        List<String> affiliations) {
}
