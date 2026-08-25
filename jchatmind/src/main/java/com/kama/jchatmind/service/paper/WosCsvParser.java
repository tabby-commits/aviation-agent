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
import java.util.Arrays;
import java.util.List;

/**
 * WoS 导出 CSV 解析器（表头制式：PT,AU,TI,SO,DT,PY,AB,TC,C1,UT,DI,...）
 * 处理要点：UTF-8 BOM、引号内逗号、分号分隔多值字段（AU/C1）
 */
@Component
public class WosCsvParser {

    public List<Paper> parse(InputStream in) throws IOException {
        List<Paper> papers = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            // 跳过 UTF-8 BOM，否则首列表头读作 "﻿PT"
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
        // 含表头的物理行号近似
        int lineNumber = (int) record.getRecordNumber() + 1;

        String ut = get(record, "UT");
        if (ut == null || ut.isBlank()) {
            throw new PaperParseException("WoS 记录缺少 UT 唯一标识，无法导入", lineNumber);
        }

        List<String> authors = splitBySemicolon(get(record, "AU"));
        List<String> affiliations = splitBySemicolon(get(record, "C1"));

        return Paper.builder()
                .docId(ut.trim())
                .sourceDb("WOS")
                .title(orNull(get(record, "TI")))
                .abstractText(orNull(get(record, "AB")))
                .authors(PaperJson.toJson(authors))
                .affiliations(PaperJson.toJson(affiliations))
                .firstAuthor(firstOrNull(authors))
                .firstAuthorAffiliation(firstOrNull(affiliations))
                .publishYear(parseInteger(get(record, "PY")))
                .journal(orNull(get(record, "SO")))
                .docType(orNull(get(record, "DT")))
                .citedCount(parseInteger(get(record, "TC")))
                .doi(orNull(get(record, "DI")))
                .screeningStatus("pending")
                .build();
    }

    private String firstOrNull(List<String> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /** 按分号+可选空格分割多值字段，返回 null 保持列可空 */
    private List<String> splitBySemicolon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split("\\s*;\\s*"))
                .filter(s -> !s.isBlank())
                .toList();
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
            return null; // 年份/被引字段格式异常时容错为 null
        }
    }
}
