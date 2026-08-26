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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 论文全文入库实现：
 * included 论文 → PDF 按页切分 → bge-m3 嵌入 → chunk_bge_m3（与新闻 KB 并列的论文全文 KB）
 * 幂等锚点：document 表中 KB+filename 已存在即跳过
 * 并发模式：复用 docProcessExecutor（每篇一个任务，CountDownLatch 汇聚，参照批量上传管道）
 */
@Slf4j
@Service
public class PaperCorpusServiceImpl implements PaperCorpusService {

    /** 论文全文知识库名称（自动创建） */
    public static final String PAPER_KB_NAME = "低轨卫星星座论文全文";

    private static final int MAX_ERRORS_IN_RESPONSE = 20;

    /** 单批并发处理超时（分钟）：每篇 CPU 嵌入约 1-2 分钟，50 篇并发 16 路需 5-8 分钟 */
    private static final int BATCH_TIMEOUT_MINUTES = 30;

    private final PaperMapper paperMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final DocumentMapper documentMapper;

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    private final RagService ragService;

    private final PaperPdfParser paperPdfParser;

    private final Executor docProcessExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaperCorpusServiceImpl(PaperMapper paperMapper,
                                  KnowledgeBaseMapper knowledgeBaseMapper,
                                  DocumentMapper documentMapper,
                                  ChunkBgeM3Mapper chunkBgeM3Mapper,
                                  RagService ragService,
                                  PaperPdfParser paperPdfParser,
                                  @Qualifier("docProcessExecutor") Executor docProcessExecutor) {
        this.paperMapper = paperMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.ragService = ragService;
        this.paperPdfParser = paperPdfParser;
        this.docProcessExecutor = docProcessExecutor;
    }

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

        // 并发处理本批（每篇一个任务；计数与错误收集用并发安全结构）
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger chunksCreated = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();

        // 先同步筛掉目录中缺失的文件（不占用线程任务）
        List<Paper> toProcess = new ArrayList<>();
        for (Paper paper : pending) {
            if (Files.isRegularFile(dir.resolve(paper.getFileName()))) {
                toProcess.add(paper);
            } else {
                missing.incrementAndGet();
            }
        }

        CountDownLatch latch = new CountDownLatch(toProcess.size());
        for (Paper paper : toProcess) {
            docProcessExecutor.execute(() -> {
                try {
                    int chunks = importSinglePaper(kb.getId(), dir.resolve(paper.getFileName()), paper);
                    if (chunks > 0) {
                        processed.incrementAndGet();
                        chunksCreated.addAndGet(chunks);
                    } else {
                        failed.incrementAndGet();
                        addError(errors, paper, "PDF 无可提取文本");
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    addError(errors, paper, e.getMessage());
                    log.warn("论文全文导入失败 docId={} file={}", paper.getDocId(), paper.getFileName(), e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(BATCH_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                addError(errors, pending.get(0), "批次处理超时（" + BATCH_TIMEOUT_MINUTES + " 分钟）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("论文全文导入被中断");
        }

        // 剩余估算：扣除已导入、本批成功与本批确认缺失（missing 换目录后重调可重试）
        int remaining = totalIncluded - existingByName.size() - processed.get() - missing.get();
        log.info("论文全文导入完成 kb={} processed={} chunks={} failed={} missing={} skippedExisting={} remaining={}",
                kb.getId(), processed.get(), chunksCreated.get(), failed.get(), missing.get(), skippedExisting, remaining);
        return CorpusImportResponse.builder()
                .kbId(kb.getId())
                .processed(processed.get())
                .chunksCreated(chunksCreated.get())
                .failed(failed.get())
                .missing(missing.get())
                .skippedExisting(skippedExisting)
                .remaining(Math.max(remaining, 0))
                .errors(errors.stream().limit(MAX_ERRORS_IN_RESPONSE).toList())
                .build();
    }

    /** 导入单篇论文（并发任务体）：解析→建 document→嵌入→逐块入库；返回分块数（0=无可提取文本） */
    private int importSinglePaper(String kbId, Path pdf, Paper paper) throws Exception {
        List<PaperPdfParser.PdfChunk> chunks = parsePdf(pdf);
        if (chunks.isEmpty()) {
            return 0;
        }

        // 先建 document 记录（幂等锚点），分块挂其下
        Document document = Document.builder()
                .kbId(kbId)
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
                    .kbId(kbId)
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
        }
        return chunks.size();
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
