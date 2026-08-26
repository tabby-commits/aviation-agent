package com.kama.jchatmind.service.paper;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 论文 PDF 解析器：按页提取文本，超长页二次切分
 * 切分单位为页（PDF 无可靠章节标记），页码写入分块元数据，与参数证据的页码可对齐回查
 */
@Component
public class PaperPdfParser {

    /** 单块最大字符数（bge-m3 嵌入安全窗口，中文论文单页通常 3000-5000 字符） */
    public static final int MAX_CHUNK_CHARS = 4000;

    public record PdfChunk(int pageNumber, String content) {
    }

    public List<PdfChunk> parse(InputStream in) throws IOException {
        List<PdfChunk> chunks = new ArrayList<>();
        byte[] bytes = in.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).trim();
                if (text.isEmpty()) {
                    continue; // 空白页（图表页）跳过
                }
                if (text.length() <= MAX_CHUNK_CHARS) {
                    chunks.add(new PdfChunk(page, text));
                } else {
                    // 超长页二次切分，页码保持原页
                    for (int start = 0; start < text.length(); start += MAX_CHUNK_CHARS) {
                        chunks.add(new PdfChunk(page,
                                text.substring(start, Math.min(text.length(), start + MAX_CHUNK_CHARS))));
                    }
                }
            }
        }
        return chunks;
    }
}
