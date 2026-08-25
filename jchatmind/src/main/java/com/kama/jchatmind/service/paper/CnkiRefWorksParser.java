package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CNKI RefWorks 制式解析器
 * 制式：每条记录由 "XX 值" 行构成（RT/A1/AD/T1/JF/YR/AB/K1/DO 等），记录间以空行分隔
 * docId 构造规则：CNKI: + md5Hex(title|journal|year)，CNKI 导出无 UT 唯一标识
 */
@Component
public class CnkiRefWorksParser {

    public List<Paper> parse(InputStream in) throws IOException {
        List<Paper> papers = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    addIfValid(current, papers);
                    current = new LinkedHashMap<>();
                } else if (line.length() >= 2) {
                    // 行格式：两位代码 + 空格 + 值（如 "T1 标题"）
                    String code = line.substring(0, 2);
                    String value = line.length() > 3 ? line.substring(3) : "";
                    current.put(code, value);
                }
            }
        }
        // 文件末尾无空行时的最后一条记录
        addIfValid(current, papers);
        return papers;
    }

    private void addIfValid(Map<String, String> fields, List<Paper> papers) {
        if (fields.isEmpty()) {
            return;
        }
        String title = orNull(fields.get("T1"));
        if (title == null) {
            return; // 无标题无法构造稳定 docId，跳过
        }
        String journal = orNull(fields.get("JF"));
        String yearStr = orNull(fields.get("YR"));

        List<String> authors = splitBySemicolon(fields.get("A1"));
        List<String> affiliations = splitBySemicolon(fields.get("AD"));

        papers.add(Paper.builder()
                .docId("CNKI:" + md5Hex(title + "|" + journal + "|" + yearStr))
                .sourceDb("CNKI")
                .title(title)
                .abstractText(orNull(fields.get("AB")))
                .authors(PaperJson.toJson(authors))
                .affiliations(PaperJson.toJson(affiliations))
                .firstAuthor(authors == null || authors.isEmpty() ? null : authors.get(0))
                .firstAuthorAffiliation(affiliations == null || affiliations.isEmpty() ? null : affiliations.get(0))
                .publishYear(parseInteger(yearStr))
                .journal(journal)
                .docType(orNull(fields.get("RT")))
                .doi(orNull(fields.get("DO")))
                .keywords(orNull(fields.get("K1")))
                .screeningStatus("pending")
                .build());
    }

    /** 分号分隔多值字段（A1/AD），过滤空段（含尾部分号） */
    private List<String> splitBySemicolon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String s : value.split(";")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? null : parts;
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

    /** 供测试断言 docId 构造规则 */
    public static String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
