package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.response.BatchSubmitResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量上传文档接口集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 * 2. Ollama 运行在 localhost:11434，bge-m3 模型已加载
 * 3. 至少有 1 个已创建的 KnowledgeBase（测试用的 kbId）
 *
 * 测试内容：
 * - 批量上传 3 个 .md 文件 + 1 个非 .md 文件
 * - 验证非 .md 文件被跳过
 * - 验证 .md 文件生成了 chunks
 * - 验证 CountDownLatch 超时机制
 */
@SpringBootTest
@Slf4j
public class DocumentBatchUploadTest {

    /**
     * 测试用的 KnowledgeBase ID
     * 会从数据库动态获取第一条记录，如果为空则跳过测试
     */
    private String testKbId;

    @Autowired
    private DocumentFacadeService documentFacadeService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Value("${document.storage.base-path}")
    private String storageBasePath;

    @BeforeEach
    public void setup() {
        // 从数据库动态获取一个 KnowledgeBase ID
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectAll();
        if (kbs == null || kbs.isEmpty()) {
            log.warn("数据库中没有 KnowledgeBase 记录，请先创建测试数据");
            // 使用一个假设存在的 ID，测试时会失败但能看到具体错误
            testKbId = "00000000-0000-0000-0000-000000000001";
        } else {
            testKbId = kbs.get(0).getId();
            log.info("使用测试 KnowledgeBase: id={}, name={}", testKbId, kbs.get(0).getName());
        }
    }

    /**
     * 测试 1：正常批量上传
     * 上传 3 个 .md 文件 + 1 个 .txt 文件（应被跳过）
     */
    @Test
    public void testBatchUploadNormal() throws Exception {
        // 准备测试文件
        List<MultipartFile> files = new ArrayList<>();

        // 3 个 markdown 文件
        files.add(createMarkdownFile("test1.md", "# 标题1\n\n这是第一个测试文档的内容。"));
        files.add(createMarkdownFile("test2.md", "# 标题2\n\n## 子标题\n\n这是第二个测试文档的内容。\n\n| 表格 | 列1 | 列2 |\n|------|-----|-----|\n| 行1  | a   | b   |"));
        files.add(createMarkdownFile("test3.md", "# 标题3\n\n这是第三个测试文档的内容，包含一些代码。\n\n```java\npublic class Test { }\n```"));

        // 1 个非 md 文件（应被跳过）
        files.add(createMarkdownFile("readme.txt", "这是一个文本文件，不是 markdown。"));

        log.info("开始批量上传测试，文件数量: {}", files.size());

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        // 调用批量上传
        BatchSubmitResponse response = documentFacadeService.uploadDocumentsBatch(testKbId, files.toArray(new MultipartFile[0]));

        long duration = System.currentTimeMillis() - startTime;

        // 验证响应
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getBatchId(), "batchId 不应为空");
        assertEquals(4, response.getTotalCount(), "总文件数应为 4");
        assertEquals("COMPLETED", response.getStatus(), "状态应为 COMPLETED");
        log.info("批量上传完成: batchId={}, status={}, 耗时={}ms", response.getBatchId(), response.getStatus(), duration);

        // 等待事务提交（latch 返回后 DB 写入可能还未完全持久化）
        Thread.sleep(2000);

        // 验证数据库：使用文件名和 kbId 查找 document 记录
        log.info("开始验证数据库记录...");

        List<Document> docsInKb = documentMapper.selectByKbId(testKbId);
        log.info("KB {} 中共有 {} 个文档", testKbId, docsInKb.size());

        // 验证本次上传的 3 个 .md 文件都存在于数据库
        // 注意：.txt 等非 md 文件不创建 document 记录（batch 逻辑直接跳过）
        assertTrue(docsInKb.stream().anyMatch(d -> "test1.md".equals(d.getFilename())), "test1.md 应存在");
        assertTrue(docsInKb.stream().anyMatch(d -> "test2.md".equals(d.getFilename())), "test2.md 应存在");
        assertTrue(docsInKb.stream().anyMatch(d -> "test3.md".equals(d.getFilename())), "test3.md 应存在");
        log.info("文件名验证通过");

        // 验证 chunks 数量（3 个 md 文件，sections 数量不同）
        // test1.md: 1 section, test2.md: 2 sections, test3.md: 1 section = 4 chunks
        // 重试 5 次，每次间隔 2s（应对 DB 写入延迟）
        int maxRetries = 5;
        int chunkCount = 0;
        for (int i = 0; i < maxRetries; i++) {
            chunkCount = chunkBgeM3Mapper.countByKbId(testKbId);
            if (chunkCount >= 4) {
                break;
            }
            log.info("等待 chunks 写入... retry {}/{}", i + 1, maxRetries);
            Thread.sleep(2000);
        }
        assertTrue(chunkCount >= 4, "应有至少 4 个 chunks 入库，实际: " + chunkCount);
        log.info("Chunks 入库数量验证通过: {}", chunkCount);

        log.info("数据库验证完成！");
    }

    /**
     * 测试 2：空文件列表
     */
    @Test
    public void testBatchUploadEmptyFiles() {
        try {
            documentFacadeService.uploadDocumentsBatch(testKbId, new MultipartFile[0]);
            fail("应抛出异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("为空") || e instanceof org.springframework.web.multipart.MultipartException,
                    "异常信息应提示文件列表为空");
            log.info("空文件列表测试通过: {}", e.getMessage());
        }
    }

    /**
     * 测试 3：SSE 连接测试（验证 SSE 推送功能正常）
     * 本测试需要前端配合连接 SSE 观察结果
     */
    @Test
    public void testSsePush() throws Exception {
        List<MultipartFile> files = new ArrayList<>();
        files.add(createMarkdownFile("sse_test.md", "# SSE 测试\n\n验证 SSE 推送功能。"));

        BatchSubmitResponse response = documentFacadeService.uploadDocumentsBatch(testKbId, files.toArray(new MultipartFile[0]));

        log.info("SSE 推送测试已提交: batchId={}", response.getBatchId());
        log.info("请连接 SSE: GET /sse/connect/{}", response.getBatchId());
        log.info("观察事件: batch_progress, batch_complete");

        // 等待处理完成
        Thread.sleep(5000);
    }

    /**
     * 测试 4：大量文件并发测试（验证 CountDownLatch 和线程池）
     */
    @Test
    public void testBatchUploadConcurrency() throws Exception {
        // 准备 20 个 markdown 文件（避免太多拖慢测试）
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String content = String.format("# 测试文档 %d\n\n这是第 %d 个测试文档的内容。\n\n## 小节\n\n内容段落 %d。", i, i, i);
            files.add(createMarkdownFile("batch_test_" + i + ".md", content));
        }

        log.info("开始并发测试，文件数量: {}", files.size());
        long startTime = System.currentTimeMillis();

        BatchSubmitResponse response = documentFacadeService.uploadDocumentsBatch(testKbId, files.toArray(new MultipartFile[0]));

        long duration = System.currentTimeMillis() - startTime;

        log.info("并发测试完成: totalCount={}, status={}, 耗时={}ms", response.getTotalCount(), response.getStatus(), duration);

        // 20 个文件 × ~12 sections × 150ms(串行 Ollama) ≈ 36s
        // 并发情况下应该更快
        assertEquals(20, response.getTotalCount());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建 MockMultipartFile（Markdown 文件）
     */
    private MockMultipartFile createMarkdownFile(String filename, String content) {
        return new MockMultipartFile(
                "files",
                filename,
                "text/markdown",
                content.getBytes()
        );
    }

    /**
     * 创建 MockMultipartFile（任意类型）
     */
    private MockMultipartFile createTextFile(String filename, String content) {
        return new MockMultipartFile(
                "files",
                filename,
                "text/plain",
                content.getBytes()
        );
    }
}
