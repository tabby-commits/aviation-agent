package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.ParameterEvidence;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 参数证据 CSV 解析器（confirmed_parameters_final.csv 制式，57 列选 23 列入库）
 * 数值列（value_min/value_max）解析失败时容错为 null
 */
@Component
public class ParametersCsvParser {

    public List<ParameterEvidence> parse(InputStream in) throws IOException {
        List<ParameterEvidence> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.mark(1);
            int firstChar = reader.read();
            if (firstChar != '﻿' && firstChar != -1) {
                reader.reset();
            }

            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .build();
            try (CSVParser csvParser = new CSVParser(reader, format)) {
                for (CSVRecord record : csvParser) {
                    ParameterEvidence evidence = toEvidence(record);
                    if (evidence != null) {
                        result.add(evidence);
                    }
                }
            }
        }
        return result;
    }

    private ParameterEvidence toEvidence(CSVRecord record) {
        String decisionId = orNull(get(record, "decision_id"));
        String docId = orNull(get(record, "doc_id"));
        // 无幂等键或无来源论文的行无法入库
        if (decisionId == null || docId == null) {
            return null;
        }
        return ParameterEvidence.builder()
                .decisionId(decisionId)
                .docId(docId)
                .technicalObject(orNull(get(record, "technical_object")))
                .parameterNameRaw(orNull(get(record, "parameter_name_raw")))
                .parameterNameCanonical(orNull(get(record, "parameter_name_canonical")))
                .parameterFamily(orNull(get(record, "parameter_family")))
                .valueRaw(orNull(get(record, "value_raw")))
                .comparator(orNull(get(record, "comparator")))
                .valueMin(parseDouble(get(record, "value_min")))
                .valueMax(parseDouble(get(record, "value_max")))
                .unitRaw(orNull(get(record, "unit_raw")))
                .unitNormalized(orNull(get(record, "unit_normalized")))
                .conditionText(orNull(get(record, "condition_text")))
                .evidenceText(orNull(get(record, "evidence_text")))
                .pageNumber(parseInteger(get(record, "page_number")))
                .section(orNull(get(record, "section")))
                .sourceType(orNull(get(record, "source_type")))
                .leafPath(orNull(get(record, "leaf_path")))
                .resultForm(orNull(get(record, "result_form")))
                .reviewStatus(orNull(get(record, "review_status")))
                .firstAuthorCountry(orNull(get(record, "first_author_country")))
                .title(orNull(get(record, "title")))
                .build();
    }

    private String get(CSVRecord record, String column) {
        return record.isSet(column) ? record.get(column) : null;
    }

    private String orNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer parseInteger(String value) {
        String v = orNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        String v = orNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
