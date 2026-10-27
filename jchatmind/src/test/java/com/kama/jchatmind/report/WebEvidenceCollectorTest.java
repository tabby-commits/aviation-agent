package com.kama.jchatmind.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebEvidenceCollectorTest {
    @Test
    void extractsTextFromCapturedPdfBytes() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("Falcon booster completed 30 flights.");
                stream.endText();
            }
            document.save(out);
            bytes = out.toByteArray();
        }
        WebEvidenceCollector collector = new WebEvidenceCollector(null, null, "data/report_runs");
        assertTrue(collector.extractPdf(bytes).contains("Falcon booster completed 30 flights."));
    }
}
