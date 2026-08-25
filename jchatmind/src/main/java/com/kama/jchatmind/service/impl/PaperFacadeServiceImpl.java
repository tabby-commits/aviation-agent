package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.mapper.PaperTaxonomyMapper;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.GetPaperResponse;
import com.kama.jchatmind.model.response.GetPapersResponse;
import com.kama.jchatmind.model.response.MembershipImportResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.model.response.PaperImportStatsResponse;
import com.kama.jchatmind.model.response.PaperScreeningImportResponse;
import com.kama.jchatmind.model.response.PaperSummary;
import com.kama.jchatmind.service.PaperFacadeService;
import com.kama.jchatmind.service.paper.CnkiRefWorksParser;
import com.kama.jchatmind.service.paper.MembersCsvParser;
import com.kama.jchatmind.service.paper.ScreeningCsvParser;
import com.kama.jchatmind.service.paper.WosCsvParser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 论文元数据导入实现：解析 → 逐条判存在 → upsert → 汇总统计
 */
@Slf4j
@Service
@AllArgsConstructor
public class PaperFacadeServiceImpl implements PaperFacadeService {

    private static final int MAX_ERRORS_IN_RESPONSE = 20;

    private final WosCsvParser wosCsvParser;

    private final CnkiRefWorksParser cnkiRefWorksParser;

    private final ScreeningCsvParser screeningCsvParser;

    private final MembersCsvParser membersCsvParser;

    private final PaperMapper paperMapper;

    private final PaperTaxonomyMapper paperTaxonomyMapper;

    @Override
    public PaperImportResponse importMetadata(MultipartFile file, String source) {
        if (file == null || file.isEmpty()) {
            throw new BizException("导入文件不能为空");
        }
        String normalizedSource = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);

        List<Paper> papers;
        try (InputStream in = file.getInputStream()) {
            papers = switch (normalizedSource) {
                case "WOS" -> wosCsvParser.parse(in);
                case "CNKI" -> cnkiRefWorksParser.parse(in);
                default -> throw new BizException("不支持的来源库：" + source + "，仅支持 WOS / CNKI");
            };
        } catch (IOException e) {
            throw new BizException("读取导入文件失败：" + e.getMessage());
        }

        int inserted = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Paper paper : papers) {
            try {
                if (paperMapper.selectByDocId(paper.getDocId()) == null) {
                    inserted++;
                } else {
                    updated++;
                }
                paperMapper.upsert(paper);
            } catch (Exception e) {
                failed++;
                if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
                    errors.add(paper.getDocId() + ": " + e.getMessage());
                }
                log.warn("论文导入失败 docId={}", paper.getDocId(), e);
            }
        }

        log.info("论文元数据导入完成 source={} total={} inserted={} updated={} failed={}",
                normalizedSource, papers.size(), inserted, updated, failed);
        return PaperImportResponse.builder()
                .sourceDb(normalizedSource)
                .total(papers.size())
                .inserted(inserted)
                .updated(updated)
                .failed(failed)
                .errors(errors)
                .build();
    }

    @Override
    public PaperScreeningImportResponse importScreening(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("导入文件不能为空");
        }

        List<Paper> records;
        try (InputStream in = file.getInputStream()) {
            records = screeningCsvParser.parse(in);
        } catch (IOException e) {
            throw new BizException("读取导入文件失败：" + e.getMessage());
        }

        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Paper record : records) {
            if (record.getDocId() == null) {
                skipped++;
                continue;
            }
            try {
                int rows = paperMapper.updateScreening(record);
                if (rows == 0) {
                    skipped++; // paper 表中无此 doc_id（元数据未导入）
                } else {
                    updated++;
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
                    errors.add(record.getDocId() + ": " + e.getMessage());
                }
                log.warn("筛选结论导入失败 docId={}", record.getDocId(), e);
            }
        }

        log.info("筛选结论导入完成 total={} updated={} skipped={} failed={}",
                records.size(), updated, skipped, failed);
        return PaperScreeningImportResponse.builder()
                .total(records.size())
                .updated(updated)
                .skipped(skipped)
                .failed(failed)
                .errors(errors)
                .build();
    }

    @Override
    public MembershipImportResponse importTaxonomyMemberships(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("导入文件不能为空");
        }

        List<MembersCsvParser.ParsedMembership> records;
        try (InputStream in = file.getInputStream()) {
            records = membersCsvParser.parse(in);
        } catch (IOException e) {
            throw new BizException("读取导入文件失败：" + e.getMessage());
        }

        int importedCount = 0;
        int candidateSkipped = 0;
        int unmappedSkipped = 0;
        int noPaperSkipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (MembersCsvParser.ParsedMembership record : records) {
            if (record.membership.getDocId() == null) {
                failed++;
                continue;
            }
            if (!record.assigned) {
                candidateSkipped++; // 弱关联（candidate_cluster）不入库
                continue;
            }
            if (record.unmapped) {
                unmappedSkipped++;
                if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
                    errors.add("未匹配一级类目: " + record.membership.getDocId());
                }
                continue;
            }
            try {
                if (paperMapper.selectByDocId(record.membership.getDocId()) == null) {
                    noPaperSkipped++; // 元数据未导入，归属无处挂靠
                    continue;
                }
                if (paperTaxonomyMapper.insertIgnore(record.membership) > 0) {
                    importedCount++;
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
                    errors.add(record.membership.getDocId() + ": " + e.getMessage());
                }
                log.warn("归属导入失败 docId={}", record.membership.getDocId(), e);
            }
        }

        log.info("论文归属导入完成 total={} imported={} candidateSkipped={} unmappedSkipped={} noPaperSkipped={} failed={}",
                records.size(), importedCount, candidateSkipped, unmappedSkipped, noPaperSkipped, failed);
        return MembershipImportResponse.builder()
                .total(records.size())
                .imported(importedCount)
                .candidateSkipped(candidateSkipped)
                .unmappedSkipped(unmappedSkipped)
                .noPaperSkipped(noPaperSkipped)
                .failed(failed)
                .errors(errors)
                .build();
    }

    @Override
    public GetPapersResponse getPapers(PaperQueryRequest query) {
        long total = paperMapper.countByCondition(query);
        List<PaperSummary> papers = paperMapper.selectByCondition(query).stream()
                .map(this::toSummary)
                .toList();
        return GetPapersResponse.builder()
                .total(total)
                .page(query.getPage())
                .pageSize(query.getPageSize())
                .papers(papers)
                .build();
    }

    @Override
    public GetPaperResponse getPaper(String docId) {
        Paper paper = paperMapper.selectByDocId(docId);
        if (paper == null) {
            throw new BizException("论文不存在：" + docId);
        }
        return GetPaperResponse.builder()
                .docId(paper.getDocId())
                .sourceDb(paper.getSourceDb())
                .title(paper.getTitle())
                .abstractText(paper.getAbstractText())
                .authors(paper.getAuthors())
                .affiliations(paper.getAffiliations())
                .firstAuthor(paper.getFirstAuthor())
                .firstAuthorAffiliation(paper.getFirstAuthorAffiliation())
                .firstAuthorCountry(paper.getFirstAuthorCountry())
                .countryEvidence(paper.getCountryEvidence())
                .countryConfidence(paper.getCountryConfidence())
                .publishYear(paper.getPublishYear())
                .journal(paper.getJournal())
                .docType(paper.getDocType())
                .citedCount(paper.getCitedCount())
                .doi(paper.getDoi())
                .keywords(paper.getKeywords())
                .screeningStatus(paper.getScreeningStatus())
                .excludeReason(paper.getExcludeReason())
                .fileName(paper.getFileName())
                .build();
    }

    @Override
    public PaperImportStatsResponse getImportStats() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : paperMapper.countGroupBySourceDb()) {
            long cnt = ((Number) row.get("cnt")).longValue();
            bySource.put(String.valueOf(row.get("source_db")), cnt);
            total += cnt;
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : paperMapper.countGroupByScreeningStatus()) {
            byStatus.put(String.valueOf(row.get("screening_status")), ((Number) row.get("cnt")).longValue());
        }
        return PaperImportStatsResponse.builder()
                .bySourceDb(bySource)
                .byScreeningStatus(byStatus)
                .total(total)
                .build();
    }

    private PaperSummary toSummary(Paper paper) {
        return PaperSummary.builder()
                .docId(paper.getDocId())
                .sourceDb(paper.getSourceDb())
                .title(paper.getTitle())
                .firstAuthor(paper.getFirstAuthor())
                .firstAuthorCountry(paper.getFirstAuthorCountry())
                .publishYear(paper.getPublishYear())
                .journal(paper.getJournal())
                .docType(paper.getDocType())
                .citedCount(paper.getCitedCount())
                .screeningStatus(paper.getScreeningStatus())
                .build();
    }
}
