package com.kama.jchatmind.service.paper;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 论文 PDF 解析器单元测试（测试内用 PDFBox 生成样例 PDF）
 */
public class PaperPdfParserTest {

    private final PaperPdfParser parser = new PaperPdfParser();

    /** 生成 n 页 PDF，每页一行文本 */
    private byte[] buildPdf(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    public void shouldExtractTextPerPage() throws IOException {
        byte[] pdf = buildPdf("LEO satellite page one", "Constellation routing page two");
        List<PaperPdfParser.PdfChunk> chunks = parser.parse(new ByteArrayInputStream(pdf));

        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).pageNumber());
        assertTrue(chunks.get(0).content().contains("page one"));
        assertEquals(2, chunks.get(1).pageNumber());
        assertTrue(chunks.get(1).content().contains("page two"));
    }

    @Test
    public void shouldSplitOverlongPage() throws IOException {
        // 超过 MAX_CHUNK_CHARS 的单页应二次切分为多块，页码保持
        String longText = "A".repeat(PaperPdfParser.MAX_CHUNK_CHARS * 2 + 100);
        byte[] pdf = buildPdf(longText);
        List<PaperPdfParser.PdfChunk> chunks = parser.parse(new ByteArrayInputStream(pdf));

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.pageNumber() == 1));
        assertEquals(PaperPdfParser.MAX_CHUNK_CHARS, chunks.get(0).content().length());
        assertEquals(PaperPdfParser.MAX_CHUNK_CHARS, chunks.get(1).content().length());
        assertEquals(100, chunks.get(2).content().length());
    }

    @Test
    public void emptyPdfShouldReturnNoChunks() throws IOException {
        byte[] pdf = buildPdf("only one page");
        List<PaperPdfParser.PdfChunk> chunks = parser.parse(new ByteArrayInputStream(pdf));
        assertEquals(1, chunks.size());
    }
}
