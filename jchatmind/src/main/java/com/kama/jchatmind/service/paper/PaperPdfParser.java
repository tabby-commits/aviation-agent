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
 * 论文 PDF 解析器：按页提取文本 → 章节状态机过滤（背景/综述类章节不嵌入）→ 超长页二次切分
 * 切分单位为页，页码写入分块元数据，与参数证据的页码可对齐回查；
 * 章节状态跨页保持（如 Introduction 跨页时后续页继续排除，直到出现保留类标题）；
 * 全文无任何可识别标题时不过滤（兜底保留全文）。
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

            PaperSectionFilter.SectionState state = PaperSectionFilter.SectionState.KEPT;

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) {
                    continue; // 空白页（图表页）跳过
                }

                // 逐行扫描：标题行切换章节状态，非排除态的行进入本页保留文本
                StringBuilder kept = new StringBuilder();
                for (String line : text.split("\r?\n", -1)) {
                    PaperSectionFilter.SectionState verdict = PaperSectionFilter.classifyHeading(line);
                    if (verdict != null) {
                        state = verdict;
                        continue; // 标题行本身不入块
                    }
                    if (state == PaperSectionFilter.SectionState.KEPT) {
                        kept.append(line).append('\n');
                    }
                }

                String keptText = kept.toString().trim();
                if (keptText.isEmpty()) {
                    continue;
                }
                if (keptText.length() <= MAX_CHUNK_CHARS) {
                    chunks.add(new PdfChunk(page, keptText));
                } else {
                    // 超长页二次切分，页码保持原页
                    for (int start = 0; start < keptText.length(); start += MAX_CHUNK_CHARS) {
                        chunks.add(new PdfChunk(page,
                                keptText.substring(start, Math.min(keptText.length(), start + MAX_CHUNK_CHARS))));
                    }
                }
            }
        }
        return chunks;
    }
}
