package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mapper.ParameterEvidenceMapper;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 论文全文入库实现（核心页策略 + 三阶段流水，CPU 嵌入瓶颈下的务实解）：
 * 嵌入范围 = 每篇首页块（标题+摘要，章节过滤后天然保留）+ 参数证据所在页的块
 * （用户决策"背景/综述不入库"的延伸：CPU 嵌入 22s/千字符块下全量嵌入需 28 小时，
 * 核心页约 1000 块 × 6.6s（Ollama 并行4）≈ 110 分钟）
 * 阶段1（并发）：解析 PDF + 章节过滤 + 核心页筛选，暂存内存（不建 document，崩溃无中间态）
 * 阶段2（并行4路）：大批量嵌入（配合 OLLAMA_NUM_PARALLEL=4）
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
    private static final int BATCH_TIMEOUT_MINUTES = 45;

    /** 每次提交 Ollama 嵌入的文本块数 */
    private static final int EMBED_BATCH_SIZE = 16;

    /** 嵌入阶段并行请求数（对齐 OLLAMA_NUM_PARALLEL=4） */
    private static final int EMBED_PARALLELISM = 4;

    private final PaperMapper paperMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final DocumentMapper documentMapper;

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    private final ParameterEvidenceMapper parameterEvidenceMapper;

    private final RagService ragService;

    private final PaperPdfParser paperPdfParser;

    private final Executor docProcessExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaperCorpusServiceImpl(PaperMapper paperMapper,
                                  KnowledgeBaseMapper knowledgeBaseMapper,
                                  DocumentMapper documentMapper,
                                  ChunkBgeM3Mapper chunkBgeM3Mapper,
                                  ParameterEvidenceMapper parameterEvidenceMapper,
                                  RagService ragService,
                                  PaperPdfParser paperPdfParser,
                                  @Qualifier("docProcessExecutor") Executor docProcessExecutor) {
        this.paperMapper = paperMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.parameterEvidenceMapper = parameterEvidenceMapper;
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

        // 参数证据页映射（核心页策略：docId → 证据所在页码集合）
        Map<String, Set<Integer>> evidencePages = new LinkedHashMap<>();
        for (Map<String, Object> row : parameterEvidenceMapper.selectDocPages()) {
            String docId = String.valueOf(row.get("docid"));
            Object page = row.get("pagenumber");
            if (docId != null && page instanceof Number) {
                evidencePages.computeIfAbsent(docId, k -> new java.util.LinkedHashSet<>())
                        .add(((Number) page).intValue());
            }
        }

        // ---- 阶段1：并发解析 + 章节过滤 + 核心页筛选（首页块 + 参数证据页块）----
        List<ParsedPaper> parsed = new CopyOnWriteArrayList<>();
        CountDownLatch parseLatch = new CountDownLatch(toProcess.size());
        for (Paper paper : toProcess) {
            docProcessExecutor.execute(() -> {
                try {
                    List<PaperPdfParser.PdfChunk> all = parsePdf(dir.resolve(paper.getFileName()));
                    if (all.isEmpty()) {
                        failed.incrementAndGet();
                        addError(errors, paper, "PDF 无可提取文本");
                    } else {
                        List<PaperPdfParser.PdfChunk> core = filterCoreChunks(
                                all, evidencePages.getOrDefault(paper.getDocId(), Set.of()));
                        parsed.add(new ParsedPaper(paper, Files.size(dir.resolve(paper.getFileName())), core));
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

        // ---- 阶段2：并行嵌入（批次任务提交线程池，信号量对齐 OLLAMA_NUM_PARALLEL=4）----
        List<String> allTexts = new ArrayList<>();
        for (ParsedPaper p : parsed) {
            p.chunks().forEach(c -> allTexts.add(c.content()));
        }
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < allTexts.size(); start += EMBED_BATCH_SIZE) {
            batches.add(new ArrayList<>(allTexts.subList(start, Math.min(allTexts.size(), start + EMBED_BATCH_SIZE))));
        }
        List<float[]> embeddings = java.util.Collections.synchronizedList(new ArrayList<>(allTexts.size()));
        CountDownLatch embedLatch = new CountDownLatch(batches.size());
        java.util.concurrent.Semaphore embedPermits = new java.util.concurrent.Semaphore(EMBED_PARALLELISM);
        for (List<String> batch : batches) {
            docProcessExecutor.execute(() -> {
                try {
                    embedPermits.acquire();
                    try {
                        embeddings.addAll(ragService.embedBatch(batch));
                    } finally {
                        embedPermits.release();
                    }
                } catch (Exception e) {
                    log.error("嵌入批次失败（{} 块）", batch.size(), e);
                } finally {
                    embedLatch.countDown();
                }
            });
        }
        awaitLatch(embedLatch, "批量嵌入");
        // 嵌入失败会导致 embeddings 数量少于文本数——按序对齐不可靠时放弃本批落库，交由重试
        if (embeddings.size() != allTexts.size()) {
            throw new BizException("嵌入数量不一致（期望 " + allTexts.size() + "，实际 " + embeddings.size()
                    + "），本批已放弃，可重试");
        }

        // ---- 阶段3：并发落库（先建 document 幂等锚点，再写分块）----
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger chunksCreated = new AtomicInteger();
        CountDownLatch writeLatch = new CountDownLatch(parsed.size());
        final List<float[]> embeddingList = embeddings;
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
                                .embedding(embeddingList.get(offsets[taskIdx] + i))
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

    /**
     * 核心页筛选：首页块（标题+摘要，章节过滤后首个保留块）+ 参数证据页的所有块
     * evidencePages 为空时仅保留首页块（无参数证据的论文）
     */
    private List<PaperPdfParser.PdfChunk> filterCoreChunks(List<PaperPdfParser.PdfChunk> all,
                                                           Set<Integer> evidencePages) {
        if (all.isEmpty()) {
            return all;
        }
        int firstPage = all.get(0).pageNumber();
        List<PaperPdfParser.PdfChunk> core = new ArrayList<>();
        for (PaperPdfParser.PdfChunk chunk : all) {
            boolean isFirstPage = chunk.pageNumber() == firstPage;
            if (isFirstPage || evidencePages.contains(chunk.pageNumber())) {
                core.add(chunk);
            }
        }
        return core;
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
