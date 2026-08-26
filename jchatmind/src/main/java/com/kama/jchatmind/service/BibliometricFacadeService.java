package com.kama.jchatmind.service;

import com.kama.jchatmind.service.bibliometric.BibliometricResult;

/**
 * 文献计量门面服务（指标（9）—（12）（16））
 */
public interface BibliometricFacadeService {

    /**
     * 基于有效研究论文（included）计算文献计量指标
     *
     * @param yearFrom 起始年
     * @param yearTo   截止年
     * @param taxonomyCode 可选：限定分类体系节点下的论文（null=全部）
     */
    BibliometricResult analyze(int yearFrom, int yearTo, String taxonomyCode);
}
