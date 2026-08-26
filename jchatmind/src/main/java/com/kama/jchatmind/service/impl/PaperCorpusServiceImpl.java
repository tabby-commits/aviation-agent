package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.response.CorpusImportResponse;
import com.kama.jchatmind.service.PaperCorpusService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.paper.PaperPdfParser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 论文全文入库实现：
 * included 论文 → PDF 按页切分 → bge-m3 嵌入 → chunk_bge_m3（与新闻 KB 并列的论文全文 KB）
 * 幂等锚点：document 表中 KB+filename 已存在即跳过
 */
@Slf4j
@Service
@AllArgsConstructor
public class PaperCorpusServiceImpl implements PaperCorpusService {

    /** 论文全文知识库名称（自动创建） */
    public static final String PAPER_KB_NAME = "低轨卫星星座论文全文";

    private static final int MAX_ERRORS_IN_RESPONSE = 20;

    private final PaperMapper paperMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final DocumentMapper documentMapper;

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    private final RagService ragService;

    private final PaperPdfParser paperPdfParser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public CorpusImportResponse importCorpus(String pdfDir, int limit) {
        if (pdfDir == null || pdfDir.isBlank()) {
            throw new BizException("pdfDir 不能为空");
        }
        Path dir = Path.of(pdfDir);
        if (!Files.isDirectory(dir)) {
            throw new BizException("PDF 目录不存在：" + pdfDir);
        }
        // 批次大小由调用方控制（真实导入建议 20-50，测试可用大值全量扫描）
        int batchLimit = Math.max(limit, 1);

        KnowledgeBase kb = ensurePaperKb();
        int totalIncluded = paperMapper.countIncludedWithFileName();

        // 已导入集合（幂等锚点）与待处理清单
        Map<String, Document> existingByName = new LinkedHashMap<>();
        for (Document doc : documentMapper.selectByKbId(kb.getId())) {
            existingByName.put(doc.getFilename(), doc);
        }

        List<Paper> pending = new ArrayList<>();
        int scanned = 0;
        int skippedExisting = 0;
        outer:
        while (pending.size() < batchLimit && scanned < totalIncluded) {
            List<Paper> page = paperMapper.selectIncludedWithFileName(200, scanned);
            if (page.isEmpty()) {
                break;
            }
            for (Paper paper : page) {
                scanned++;
                if (existingByName.containsKey(paper.getFileName())) {
                    skippedExisting++;
                    continue;
                }
                pending.add(paper);
                if (pending.size() >= batchLimit) {
                    break outer;
                }
            }
        }

        int processed = 0;
        int chunksCreated = 0;
        int failed = 0;
        int missing = 0;
        List<String> errors = new ArrayList<>();

        for (Paper paper : pending) {
            Path pdf = dir.resolve(paper.getFileName());
            if (!Files.isRegularFile(pdf)) {
                missing++;
                continue;
            }
            try {
                List<PaperPdfParser.PdfChunk> chunks = parsePdf(pdf);
                if (chunks.isEmpty()) {
                    failed++;
                    addError(errors, paper, "PDF 无可提取文本");
                    continue;
                }

                // 先建 document 记录（幂等锚点），分块挂其下
                Document document = Document.builder()
                        .kbId(kb.getId())
                        .filename(paper.getFileName())
                        .filetype("pdf")
                        .size(Files.size(pdf))
                        .metadata(objectMapper.writeValueAsString(Map.of(
                                "docId", paper.getDocId(),
                                "title", paper.getTitle() == null ? "" : paper.getTitle(),
                                "sourceType", "paper")))
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                documentMapper.insert(document);

                // 批量嵌入后逐块入库
                List<String> texts = chunks.stream().map(PaperPdfParser.PdfChunk::content).toList();
                List<float[]> embeddings = ragService.embedBatch(texts);
                for (int i = 0; i < chunks.size(); i++) {
                    PaperPdfParser.PdfChunk chunk = chunks.get(i);
                    chunkBgeM3Mapper.insert(ChunkBgeM3.builder()
                            .kbId(kb.getId())
                            .docId(document.getId())
                            .content(chunk.content())
                            .metadata(objectMapper.writeValueAsString(Map.of(
                                    "docId", paper.getDocId(),
                                    "page", chunk.pageNumber(),
                                    "fileName", paper.getFileName(),
                                    "sourceType", "paper")))
                            .embedding(embeddings.get(i))
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build());
                    chunksCreated++;
                }
                processed++;
            } catch (Exception e) {
                failed++;
                addError(errors, paper, e.getMessage());
                log.warn("论文全文导入失败 docId={} file={}", paper.getDocId(), paper.getFileName(), e);
            }
        }

        // 剩余估算：扣除已导入、本批成功与本批确认缺失（missing 换目录后重调可重试）
        int remaining = totalIncluded - existingByName.size() - processed - missing;
        log.info("论文全文导入完成 kb={} processed={} chunks={} failed={} missing={} skippedExisting={} remaining={}",
                kb.getId(), processed, chunksCreated, failed, missing, skippedExisting, remaining);
        return CorpusImportResponse.builder()
                .kbId(kb.getId())
                .processed(processed)
                .chunksCreated(chunksCreated)
                .failed(failed)
                .missing(missing)
                .skippedExisting(skippedExisting)
                .remaining(Math.max(remaining, 0))
                .errors(errors)
                .build();
    }

    private List<PaperPdfParser.PdfChunk> parsePdf(Path pdf) throws IOException {
        try (InputStream in = new FileInputStream(pdf.toFile())) {
            return paperPdfParser.parse(in);
        }
    }

    /** 论文全文 KB：不存在则创建 */
    private KnowledgeBase ensurePaperKb() {
        KnowledgeBase existing = knowledgeBaseMapper.selectByName(PAPER_KB_NAME);
        if (existing != null) {
            return existing;
        }
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(PAPER_KB_NAME)
                .description("低轨卫星星座网络有效研究论文全文（included 论文，PDF 按页切分，bge-m3 嵌入）")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeBaseMapper.insert(kb);
        return knowledgeBaseMapper.selectByName(PAPER_KB_NAME);
    }

    private void addError(List<String> errors, Paper paper, String message) {
        if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
            errors.add(paper.getDocId() + "(" + paper.getFileName() + "): " + message);
        }
    }
}
