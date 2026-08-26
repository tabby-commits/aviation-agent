package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.mapper.PaperTaxonomyMapper;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.entity.PaperTaxonomy;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.service.BibliometricFacadeService;
import com.kama.jchatmind.service.bibliometric.BibliometricCalculator;
import com.kama.jchatmind.service.bibliometric.BibliometricResult;
import com.kama.jchatmind.service.bibliometric.PaperBiblioRecord;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文献计量服务：拉取有效研究论文（included）构造计量记录，调用纯函数计算器
 */
@Service
@AllArgsConstructor
public class BibliometricFacadeServiceImpl implements BibliometricFacadeService {

    private final PaperMapper paperMapper;

    private final PaperTaxonomyMapper paperTaxonomyMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public BibliometricResult analyze(int yearFrom, int yearTo, String taxonomyCode) {
        // 分类限定：取该节点下的 docId 集合（null=不过滤）
        final Set<String> docIdFilter;
        if (taxonomyCode != null && !taxonomyCode.isBlank()) {
            List<PaperTaxonomy> memberships = paperTaxonomyMapper.selectByTaxonomyCode(taxonomyCode);
            docIdFilter = memberships.stream().map(PaperTaxonomy::getDocId).collect(Collectors.toSet());
        } else {
            docIdFilter = null;
        }

        // 有效研究论文全集（included），分页遍历
        List<PaperBiblioRecord> records = new ArrayList<>();
        PaperQueryRequest query = new PaperQueryRequest();
        query.setScreeningStatus("included");
        query.setYearFrom(yearFrom);
        query.setYearTo(yearTo);
        query.setPageSize(200);
        int page = 1;
        List<Paper> batch;
        while ((batch = paperMapper.selectByCondition(withPage(query, page))).size() == 200) {
            records.addAll(toRecords(batch, docIdFilter));
            page++;
        }
        records.addAll(toRecords(batch, docIdFilter));

        return BibliometricCalculator.calculate(records, yearFrom, yearTo);
    }

    private PaperQueryRequest withPage(PaperQueryRequest template, int page) {
        template.setPage(page);
        return template;
    }

    private List<PaperBiblioRecord> toRecords(List<Paper> papers, Set<String> docIdFilter) {
        return papers.stream()
                .map(this::toRecord)
                .filter(r -> docIdFilter == null || docIdFilter.contains(r.docId()))
                .toList();
    }

    private PaperBiblioRecord toRecord(Paper paper) {
        return new PaperBiblioRecord(
                paper.getDocId(),
                paper.getPublishYear(),
                paper.getDocType(),
                paper.getCitedCount(),
                paper.getFirstAuthorCountry(),
                parseAffiliations(paper.getAffiliations()));
    }

    /** affiliations JSON 数组字符串 → List<String>（容错返回空表） */
    private List<String> parseAffiliations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
