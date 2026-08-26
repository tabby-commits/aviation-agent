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
 * 论文全文入库实现（两阶段流水，CPU 嵌入瓶颈下的最优吞吐结构）：
 * 阶段1（并发）：解析 PDF + 章节过滤，结果暂存内存（不建 document，崩溃无中间态）
 * 阶段2（串行）：全部待嵌入文本按批提交 embedBatch —— Ollama 端串行时，
 *               少量大请求远优于多个并发小请求（避免排队与过载）
 * 阶段3（并发）：建 document（幂等锚点）+ 写分块
 * 幂等锚点：document 表中 KB+filename 已存在即跳过
 */
@Slf4j
@Service
public class PaperCorpusServiceImpl implements PaperCorpusService {

    /** 论文全文知识库名称（自动创建） */
    public static final String PAPER_KB_NAME = "低轨卫星星座论文全文";

    private static final int MAX_ERRORS_IN_RESPONSE = 20;

    /** 单批并发处理超时（分钟） */
    private static final int BATCH_TIMEOUT_MINUTES = 30;

    /** 每次提交 Ollama 嵌入的文本块数（大批量摊薄请求开销） */
    private static final int EMBED_BATCH_SIZE = 32;

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

    /** 单篇论文的解析结果（阶段1产出，内存暂存） */
    private record ParsedPaper(Paper paper, long fileSize, List<PaperPdfParser.PdfChunk> chunks) {
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

        AtomicInteger failed = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        List<String> errors = new CopyOnWriteArrayList<>();

        // 文件存在性预筛（不占任务）
        List<Paper> toProcess = new ArrayList<>();
        for (Paper paper : pending) {
            if (Files.isRegularFile(dir.resolve(paper.getFileName()))) {
                toProcess.add(paper);
            } else {
                missing.incrementAndGet();
            }
        }

        // ---- 阶段1：并发解析（纯 CPU，快）----
        List<ParsedPaper> parsed = new CopyOnWriteArrayList<>();
        CountDownLatch parseLatch = new CountDownLatch(toProcess.size());
        for (Paper paper : toProcess) {
            docProcessExecutor.execute(() -> {
                try {
                    List<PaperPdfParser.PdfChunk> chunks = parsePdf(dir.resolve(paper.getFileName()));
                    if (chunks.isEmpty()) {
                        failed.incrementAndGet();
                        addError(errors, paper, "PDF 无可提取文本");
                    } else {
                        parsed.add(new ParsedPaper(paper, Files.size(dir.resolve(paper.getFileName())), chunks));
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    addError(errors, paper, e.getMessage());
                    log.warn("论文 PDF 解析失败 docId={} file={}", paper.getDocId(), paper.getFileName(), e);
                } finally {
                    parseLatch.countDown();
                }
            });
        }
        awaitLatch(parseLatch, "PDF 解析");

        // ---- 阶段2：串行大批量嵌入（Ollama 端串行时的最优吞吐）----
        List<String> allTexts = new ArrayList<>();
        for (ParsedPaper p : parsed) {
            p.chunks().forEach(c -> allTexts.add(c.content()));
        }
        List<float[]> allEmbeddings = new ArrayList<>();
        for (int start = 0; start < allTexts.size(); start += EMBED_BATCH_SIZE) {
            List<String> batch = allTexts.subList(start, Math.min(allTexts.size(), start + EMBED_BATCH_SIZE));
            allEmbeddings.addAll(ragService.embedBatch(batch));
        }

        // ---- 阶段3：并发落库（先建 document 幂等锚点，再写分块）----
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger chunksCreated = new AtomicInteger();
        CountDownLatch writeLatch = new CountDownLatch(parsed.size());
        final List<float[]> embeddings = allEmbeddings;
        int[] offsets = new int[parsed.size() + 1];
        for (int i = 0; i < parsed.size(); i++) {
            offsets[i + 1] = offsets[i] + parsed.get(i).chunks().size();
        }
        for (int idx = 0; idx < parsed.size(); idx++) {
            final int taskIdx = idx;
            ParsedPaper p = parsed.get(idx);
            docProcessExecutor.execute(() -> {
                try {
                    Document document = Document.builder()
                            .kbId(kb.getId())
                            .filename(p.paper().getFileName())
                            .filetype("pdf")
                            .size(p.fileSize())
                            .metadata(objectMapper.writeValueAsString(Map.of(
                                    "docId", p.paper().getDocId(),
                                    "title", p.paper().getTitle() == null ? "" : p.paper().getTitle(),
                                    "sourceType", "paper")))
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    documentMapper.insert(document);

                    for (int i = 0; i < p.chunks().size(); i++) {
                        PaperPdfParser.PdfChunk chunk = p.chunks().get(i);
                        chunkBgeM3Mapper.insert(ChunkBgeM3.builder()
                                .kbId(kb.getId())
                                .docId(document.getId())
                                .content(chunk.content())
                                .metadata(objectMapper.writeValueAsString(Map.of(
                                        "docId", p.paper().getDocId(),
                                        "page", chunk.pageNumber(),
                                        "fileName", p.paper().getFileName(),
                                        "sourceType", "paper")))
                                .embedding(embeddings.get(offsets[taskIdx] + i))
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build());
                        chunksCreated.incrementAndGet();
                    }
                    processed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    addError(errors, p.paper(), e.getMessage());
                    log.warn("论文全文落库失败 docId={} file={}", p.paper().getDocId(), p.paper().getFileName(), e);
                } finally {
                    writeLatch.countDown();
                }
            });
        }
        awaitLatch(writeLatch, "分块落库");

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

    private void awaitLatch(CountDownLatch latch, String stage) {
        try {
            if (!latch.await(BATCH_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new BizException(stage + "阶段处理超时（" + BATCH_TIMEOUT_MINUTES + " 分钟）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("论文全文导入被中断");
        }
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
                .description("低轨卫星星座网络有效研究论文全文（included 论文，PDF 按页切分+章节过滤，bge-m3 嵌入）")
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
