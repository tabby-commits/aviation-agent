package com.kama.jchatmind.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ReportContractTest {
    private final CitationValidator validator = new CitationValidator();

    @Test
    void rejectsValueThatCannotBeRecalculated() {
        ReportDocument report = sample("2");
        assertThrows(IllegalStateException.class, () -> validator.validate(report));
    }

    @Test
    void wordKeepsFootnoteLinkFontsSpacingIndentAndThreeLineTable() throws Exception {
        byte[] bytes = new ReportDocxExporter(validator).export(sample("1"));
        Map<String, String> parts = unzip(bytes);
        String document = parts.get("word/document.xml");
        String notes = parts.get("word/footnotes.xml");
        assertNotNull(document);
        assertNotNull(notes);
        assertTrue(document.contains("测试报告"));
        assertTrue(document.contains("footnoteReference"));
        assertTrue(notes.contains("Example institution"));
        assertTrue(notes.contains("hyperlink"));
        assertTrue(parts.get("word/_rels/footnotes.xml.rels").contains("https://example.org/original"));
        assertTrue(document.contains("w:eastAsia=\"仿宋\""));
        assertTrue(document.contains("w:ascii=\"Times New Roman\""));
        assertTrue(document.contains("w:line=\"360\""));
        assertTrue(document.contains("w:firstLine=\"480\""));
        assertTrue(document.contains("w:tblBorders"));
        assertTrue(document.contains("w:top"));
        assertTrue(document.contains("w:bottom"));
    }

    private ReportDocument sample(String value) {
        ReportDocument.Source source = new ReportDocument.Source("s1", "external", "Example institution",
                "https://example.org/original", "section 1", "one event", "abc", Instant.now().toString(),
                "机器核验");
        ReportDocument.Calculation calc = new ReportDocument.Calculation("c1", "count distinct event_id",
                "COUNT(DISTINCT event_id)", List.of("e1"), value, "件");
        ReportDocument.Claim claim = new ReportDocument.Claim("q1", "事件 1 件。", List.of("s1"), "c1", "1", "件");
        ReportDocument.Table table = new ReportDocument.Table("事件表", List.of("年份", "数量"),
                List.of(new ReportDocument.Row(List.of("2025", "1"), List.of("s1"), List.of("c1"))));
        return new ReportDocument("r1", "run1", "session1", "leo", "测试报告", "测试问题", "snapshot1", 1, Instant.now(),
                List.of(source), List.of(calc), List.of(claim),
                List.of(new ReportDocument.Dimension("产业成熟度", List.of(new ReportDocument.Indicator("23", "产能", List.of(claim), table)))),
                List.of(claim), Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new java.util.HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                entries.put(entry.getName(), out.toString(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
