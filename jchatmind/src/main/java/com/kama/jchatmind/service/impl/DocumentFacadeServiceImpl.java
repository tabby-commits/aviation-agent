package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.BatchResultResponse;
import com.kama.jchatmind.model.response.BatchSubmitResponse;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.FileResult;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final SseService sseService;
    private final Executor docProcessExecutor;

    private static final int PROGRESS_REPORT_INTERVAL = 50;
    private static final int BATCH_TIMEOUT_MINUTES = 10;

    public DocumentFacadeServiceImpl(
            DocumentMapper documentMapper,
            DocumentConverter documentConverter,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            SseService sseService,
            @Qualifier("docProcessExecutor") Executor docProcessExecutor) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
        this.documentStorageService = documentStorageService;
        this.markdownParserService = markdownParserService;
        this.ragService = ragService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.sseService = sseService;
        this.docProcessExecutor = docProcessExecutor;
    }

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            // 如果是 Markdown 文件，进行解析并生成 chunks
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                processMarkdownDocument(kbId, documentId, filePath);
            } else {
                // TODO: 未来可以增加其他文件类型的处理逻辑
                log.warn("待新增处理的文件类型: {}", filetype);
            }

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public BatchSubmitResponse uploadDocumentsBatch(String kbId, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BizException("上传的文件列表为空");
        }

        String batchId = UUID.randomUUID().toString();
        int totalCount = files.length;
        log.info("开始批量上传: batchId={}, kbId={}, totalFiles={}", batchId, kbId, totalCount);

        // 统计 md 文件数量，用于初始化 CountDownLatch
        long mdFileCount = Arrays.stream(files)
                .filter(f -> !f.isEmpty())
                .filter(f -> {
                    String ft = getFileType(f.getOriginalFilename());
                    return "md".equalsIgnoreCase(ft) || "markdown".equalsIgnoreCase(ft);
                })
                .count();

        // 线程安全的结果收集
        List<FileResult> fileResults = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger processed = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch((int) mdFileCount);

        // 提交任务到线程池
        for (MultipartFile file : files) {
            docProcessExecutor.execute(() -> {
                try {
                    processFileBatch(file, kbId, fileResults);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("批量文件处理异常: filename={}", file.getOriginalFilename(), e);
                } finally {
                    int currentProcessed = processed.incrementAndGet();
                    latch.countDown();

                    // 每处理 PROGRESS_REPORT_INTERVAL 条推送一次进度
                    if (currentProcessed % PROGRESS_REPORT_INTERVAL == 0 || currentProcessed == totalCount) {
                        pushBatchProgress(batchId, currentProcessed, totalCount, successCount.get(), failCount.get());
                    }
                    // 超时兜底：每 100 条打印一次日志
                    if (currentProcessed % 100 == 0) {
                        log.info("批量处理进度: batchId={}, processed={}/{}", batchId, currentProcessed, totalCount);
                    }
                }
            });
        }

        // 主线程等待，最多等 BATCH_TIMEOUT_MINUTES 分钟
        boolean completed = false;
        try {
            completed = latch.await(BATCH_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("批量处理被中断: batchId={}", batchId);
        }

        String status;
        if (completed) {
            status = BatchSubmitResponse.Status.COMPLETED.name();
            log.info("批量处理完成: batchId={}, success={}, fail={}", batchId, successCount.get(), failCount.get());
        } else {
            status = BatchSubmitResponse.Status.TIMEOUT.name();
            log.warn("批量处理超时: batchId={}, processed={}/{}, success={}, fail={}",
                    batchId, processed.get(), totalCount, successCount.get(), failCount.get());
        }

        // 推送最终状态
        pushBatchComplete(batchId, status, totalCount, successCount.get(), failCount.get(), fileResults);

        return BatchSubmitResponse.builder()
                .batchId(batchId)
                .totalCount(totalCount)
                .status(status)
                .message("批次已提交，" + (completed ? "处理完成" : "处理超时"))
                .build();
    }

    private void processFileBatch(MultipartFile file, String kbId, List<FileResult> fileResults) {
        String filename = file.getOriginalFilename();
        String filetype = getFileType(filename);

        // 非 md 文件跳过
        if (!"md".equalsIgnoreCase(filetype) && !"markdown".equalsIgnoreCase(filetype)) {
            fileResults.add(FileResult.builder()
                    .filename(filename)
                    .status(FileResult.Status.SKIPPED)
                    .build());
            return;
        }

        try {
            if (file.isEmpty()) {
                fileResults.add(FileResult.builder()
                        .filename(filename)
                        .status(FileResult.Status.FAILED)
                        .error("文件为空")
                        .build());
                return;
            }

            long fileSize = file.getSize();

            // 创建文档记录
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(filename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            // 处理 Markdown，生成 chunks
            processMarkdownDocument(kbId, documentId, filePath);

            fileResults.add(FileResult.builder()
                    .filename(filename)
                    .documentId(documentId)
                    .status(FileResult.Status.SUCCESS)
                    .build());

        } catch (Exception e) {
            log.error("文件处理失败: filename={}", filename, e);
            fileResults.add(FileResult.builder()
                    .filename(filename)
                    .status(FileResult.Status.FAILED)
                    .error(e.getMessage())
                    .build());
        }
    }

    private void pushBatchProgress(String batchId, int processed, int total, int success, int fail) {
        try {
            SseMessage message = SseMessage.builder()
                    .type(SseMessage.Type.BATCH_PROGRESS)
                    .payload(SseMessage.Payload.builder()
                            .batchId(batchId)
                            .processed(processed)
                            .total(total)
                            .successCount(success)
                            .failCount(fail)
                            .build())
                    .build();
            sseService.send(batchId, message);
        } catch (Exception e) {
            log.warn("推送批量处理进度失败: batchId={}, error={}", batchId, e.getMessage());
        }
    }

    private void pushBatchComplete(String batchId, String status, int total, int success, int fail, List<FileResult> results) {
        try {
            BatchResultResponse batchResult = BatchResultResponse.builder()
                    .batchId(batchId)
                    .status(status)
                    .totalCount(total)
                    .successCount(success)
                    .failCount(fail)
                    .results(results)
                    .build();

            SseMessage message = SseMessage.builder()
                    .type(SseMessage.Type.BATCH_COMPLETE)
                    .payload(SseMessage.Payload.builder()
                            .batchResult(batchResult)
                            .build())
                    .build();
            sseService.send(batchId, message);
        } catch (Exception e) {
            log.warn("推送批量处理完成状态失败: batchId={}, error={}", batchId, e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

            // 从保存的文件路径读取文件
            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                // 解析 Markdown 文件
                List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);

                System.out.println(sections);

                if (sections.isEmpty()) {
                    log.warn("Markdown 文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                int chunkCount = 0;

                // 为每个章节生成 chunk
                for (MarkdownParserService.MarkdownSection section : sections) {
                    String title = section.getTitle();
                    String content = section.getContent();

                    if (title == null || title.trim().isEmpty()) {
                        continue;
                    }

                    // 对标题进行 embedding
                    float[] embedding = ragService.embed(title);

                    // 创建 ChunkBgeM3 实体
                    ChunkBgeM3 chunk = ChunkBgeM3.builder()
                            .kbId(kbId)
                            .docId(documentId)
                            .content(content != null ? content : "")
                            .metadata(null) // 可以存储标题信息到 metadata
                            .embedding(embedding)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    // 插入数据库
                    int result = chunkBgeM3Mapper.insert(chunk);

                    if (result > 0) {
                        chunkCount++;
                        log.debug("创建 chunk 成功: title={}, chunkId={}", title, chunk.getId());
                    } else {
                        log.warn("创建 chunk 失败: title={}", title);
                    }
                }
                log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
            }
        } catch (Exception e) {
            log.error("处理 Markdown 文档失败: documentId={}", documentId, e);
            // 不抛出异常，避免影响文档上传流程
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
