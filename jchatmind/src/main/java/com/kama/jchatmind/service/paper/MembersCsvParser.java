package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.PaperTaxonomy;
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
import java.util.Map;

/**
 * 论文归属 CSV 解析器（paper_members.csv 制式）
 * 列：node_id, doc_id, title, first_level, second_level, membership_type
 * 只处理 membership_type=level2_assignment 的正式归属；first_level（EASC 一级类目）按映射表转为新体系 code
 */
@Component
public class MembersCsvParser {

    /** EASC 一级类目 → 新分类体系 code（1:1，体系定义见 taxonomy-schema.sql 种子） */
    private static final Map<String, String> EASC_L1_TO_CODE = Map.of(
            "Constellation Design Technologies", "constellation-design",
            "Inter-Satellite Optical Communication Technologies", "optical-isl",
            "Inter-Satellite Networking Technologies", "inter-satellite-networking",
            "Space Computing Technologies", "space-computing",
            "Satellite-Terrestrial Integrated Networking Technologies", "sat-ground-integration",
            "Interference Mitigation Technologies", "interference-mitigation");

    /** 归属来源标记（与最终人工审核的 EASC V19 版本对应） */
    public static final String SOURCE = "easc_v19_level2";

    public static class ParsedMembership {
        public final PaperTaxonomy membership;
        /** true=正式归属（level2_assignment），false=弱关联（candidate_cluster） */
        public final boolean assigned;
        /** true=first_level 未命中映射表 */
        public final boolean unmapped;

        ParsedMembership(PaperTaxonomy membership, boolean assigned, boolean unmapped) {
            this.membership = membership;
            this.assigned = assigned;
            this.unmapped = unmapped;
        }
    }

    public List<ParsedMembership> parse(InputStream in) throws IOException {
        List<ParsedMembership> result = new ArrayList<>();

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
                    result.add(toMembership(record));
                }
            }
        }
        return result;
    }

    private ParsedMembership toMembership(CSVRecord record) {
        String docId = orNull(get(record, "doc_id"));
        String firstLevel = orNull(get(record, "first_level"));
        String membershipType = orNull(get(record, "membership_type"));
        boolean assigned = "level2_assignment".equalsIgnoreCase(membershipType);

        String code = firstLevel == null ? null : EASC_L1_TO_CODE.get(normalize(firstLevel));
        boolean unmapped = assigned && code == null;

        PaperTaxonomy membership = PaperTaxonomy.builder()
                .docId(docId)
                .taxonomyCode(code)
                .membershipType("assigned")
                .source(SOURCE)
                .build();
        return new ParsedMembership(membership, assigned, unmapped);
    }

    /** EASC 类目名的空格规范化（防全角空格/多空格导致映射失配） */
    private String normalize(String name) {
        return name.replaceAll("\\s+", " ").trim();
    }

    private String get(CSVRecord record, String column) {
        return record.isSet(column) ? record.get(column) : null;
    }

    private String orNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
