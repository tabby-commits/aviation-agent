package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
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
 * 筛选结论 CSV 解析器（document_screen.csv 制式）
 * 关键列：doc_id, include(True/False), first_author_country, country_evidence,
 *        country_confidence, file_name, reason
 * 产出 Paper 更新载体：screeningStatus(included/excluded) + 国别判定 + 排除原因
 */
@Component
public class ScreeningCsvParser {

    public List<Paper> parse(InputStream in) throws IOException {
        List<Paper> papers = new ArrayList<>();

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
                    papers.add(toPaper(record));
                }
            }
        }
        return papers;
    }

    private Paper toPaper(CSVRecord record) {
        String include = get(record, "include");
        // include=True → included；False/空 → excluded（筛选表的显式判定）
        String screeningStatus = "True".equalsIgnoreCase(include) ? "included" : "excluded";

        return Paper.builder()
                .docId(orNull(get(record, "doc_id")))
                .screeningStatus(screeningStatus)
                .firstAuthorCountry(orNull(get(record, "first_author_country")))
                .countryEvidence(orNull(get(record, "country_evidence")))
                .countryConfidence(orNull(get(record, "country_confidence")))
                .fileName(orNull(get(record, "file_name")))
                .excludeReason(orNull(get(record, "reason")))
                .build();
    }

    private String get(CSVRecord record, String column) {
        return record.isSet(column) ? record.get(column) : null;
    }

    private String orNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
