package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ParameterEvidenceMapper;
import com.kama.jchatmind.model.entity.ParameterEvidence;
import com.kama.jchatmind.model.request.ParameterQueryRequest;
import com.kama.jchatmind.model.response.GetParametersResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.model.response.ParameterEvidenceSummary;
import com.kama.jchatmind.service.ParameterFacadeService;
import com.kama.jchatmind.service.paper.ParametersCsvParser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 参数证据导入与查询实现
 */
@Slf4j
@Service
@AllArgsConstructor
public class ParameterFacadeServiceImpl implements ParameterFacadeService {

    private static final int MAX_ERRORS_IN_RESPONSE = 20;

    private final ParametersCsvParser parametersCsvParser;

    private final ParameterEvidenceMapper parameterEvidenceMapper;

    @Override
    public PaperImportResponse importParameters(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("导入文件不能为空");
        }

        List<ParameterEvidence> records;
        try (InputStream in = file.getInputStream()) {
            records = parametersCsvParser.parse(in);
        } catch (IOException e) {
            throw new BizException("读取导入文件失败：" + e.getMessage());
        }

        int inserted = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (ParameterEvidence evidence : records) {
            try {
                if (parameterEvidenceMapper.selectByDecisionId(evidence.getDecisionId()) == null) {
                    inserted++;
                } else {
                    updated++;
                }
                parameterEvidenceMapper.upsert(evidence);
            } catch (Exception e) {
                failed++;
                if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
                    errors.add(evidence.getDecisionId() + ": " + e.getMessage());
                }
                log.warn("参数证据导入失败 decisionId={}", evidence.getDecisionId(), e);
            }
        }

        // 从 paper 表回填发表年份（幂等：只更新不一致的行）
        int enriched = parameterEvidenceMapper.enrichPublishYear();

        log.info("参数证据导入完成 total={} inserted={} updated={} failed={} enrichedYear={}",
                records.size(), inserted, updated, failed, enriched);
        return PaperImportResponse.builder()
                .sourceDb("PARAMETER")
                .total(records.size())
                .inserted(inserted)
                .updated(updated)
                .failed(failed)
                .errors(errors)
                .build();
    }

    @Override
    public GetParametersResponse getParameters(ParameterQueryRequest query) {
        long total = parameterEvidenceMapper.countByCondition(query);
        List<ParameterEvidenceSummary> parameters = parameterEvidenceMapper.selectByCondition(query).stream()
                .map(this::toSummary)
                .toList();
        return GetParametersResponse.builder()
                .total(total)
                .page(query.getPage())
                .pageSize(query.getPageSize())
                .parameters(parameters)
                .build();
    }

    private ParameterEvidenceSummary toSummary(ParameterEvidence e) {
        return ParameterEvidenceSummary.builder()
                .decisionId(e.getDecisionId())
                .docId(e.getDocId())
                .title(e.getTitle())
                .firstAuthorCountry(e.getFirstAuthorCountry())
                .publishYear(e.getPublishYear())
                .technicalObject(e.getTechnicalObject())
                .parameterNameCanonical(e.getParameterNameCanonical())
                .parameterFamily(e.getParameterFamily())
                .valueRaw(e.getValueRaw())
                .valueMin(e.getValueMin())
                .valueMax(e.getValueMax())
                .unitNormalized(e.getUnitNormalized())
                .conditionText(e.getConditionText())
                .evidenceText(e.getEvidenceText())
                .pageNumber(e.getPageNumber())
                .section(e.getSection())
                .sourceType(e.getSourceType())
                .reviewStatus(e.getReviewStatus())
                .build();
    }
}
