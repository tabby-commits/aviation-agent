package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.response.CorpusImportResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 论文全文入库集成测试（真实 Ollama 嵌入）
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 * 2. Ollama 运行在 localhost:11434，bge-m3 模型已加载
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PaperCorpusImportTest {

    private static final String TEST_FILE = "TEST_CORPUS_0001.pdf";
    private static final String TEST_DOC_ID = "WOS:TEST-CORPUS1";

    @Autowired
    private PaperCorpusService paperCorpusService;

    @Autowired
    private PaperMapper paperMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private com.kama.jchatmind.mapper.ParameterEvidenceMapper parameterEvidenceMapper;

    private Path tmpDir;

    @BeforeAll
    public void setup() throws IOException {
        // 清理历史测试数据（KB→document→chunks 级联）
        cleanupTestData();
        paperMapper.upsert(Paper.builder()
                .docId(TEST_DOC_ID).sourceDb("WOS")
                .title("Corpus Import Test Paper")
                .screeningStatus("included")
                .fileName(TEST_FILE)
                .build());

        tmpDir = Files.createTempDirectory("jchatmind-corpus-test");
        buildTestPdf(tmpDir.resolve(TEST_FILE));
    }

    /**
     * 每个测试独立从零开始：清除上一测试导入的 document/chunks
     * （测试方法执行顺序不确定，避免 skipExisting 测试先跑导致污染）
     */
    @org.junit.jupiter.api.BeforeEach
    public void cleanImportedState() {
        cleanupTestData();
        paperMapper.upsert(Paper.builder()
                .docId(TEST_DOC_ID).sourceDb("WOS")
                .title("Corpus Import Test Paper")
                .screeningStatus("included")
                .fileName(TEST_FILE)
                .build());
    }

    @AfterAll
    public void teardown() throws IOException {
        cleanupTestData();
        if (tmpDir != null) {
            Files.deleteIfExists(tmpDir.resolve(TEST_FILE));
            Files.deleteIfExists(tmpDir);
        }
    }

    private void cleanupTestData() {
        parameterEvidenceMapper.deleteByDecisionIdPrefix("d_TEST-CORPUS");
        KnowledgeBase kb = knowledgeBaseMapper.selectByName("低轨卫星星座论文全文");
        if (kb != null) {
            for (Document doc : documentMapper.selectByKbId(kb.getId())) {
                if (TEST_FILE.equals(doc.getFilename())) {
                    chunkBgeM3Mapper.deleteByDocId(doc.getId());
                    documentMapper.deleteById(doc.getId());
                }
            }
        }
        paperMapper.deleteByDocIdPrefix("WOS:TEST-CORPUS");
    }

    @Test
    public void importCorpusShouldChunkEmbedAndIndex() {
        // limit 取大值全量扫描：真实库中 557 篇 included 在临时目录缺失（missing），测试论文成功处理
        // 预置参数证据：第 2 页为核心页（首页块 + 参数页块均保留）
        parameterEvidenceMapper.upsert(com.kama.jchatmind.model.entity.ParameterEvidence.builder()
                .decisionId("d_TEST-CORPUS-P1").docId(TEST_DOC_ID)
                .pageNumber(2).parameterFamily("latency and delay")
                .reviewStatus("reviewed_rule").firstAuthorCountry("US")
                .title("Corpus Import Test Paper").build());

        CorpusImportResponse response = paperCorpusService.importCorpus(tmpDir.toString(), 2000);

        assertEquals(1, response.getProcessed());
        assertTrue(response.getChunksCreated() >= 2, "首页块 + 参数页块（第2页）");
        assertEquals(0, response.getFailed());

        // KB 自动创建
        KnowledgeBase kb = knowledgeBaseMapper.selectByName("低轨卫星星座论文全文");
        assertNotNull(kb);

        // document 记录与分块元数据（docId/page/sourceType）
        Document doc = documentMapper.selectByKbIdAndFilename(kb.getId(), TEST_FILE);
        assertNotNull(doc);
        List<String> chunkIds = chunkBgeM3Mapper.selectChunkIdsByDocId(doc.getId());
        assertEquals(response.getChunksCreated(), chunkIds.size());

        var chunk = chunkBgeM3Mapper.selectById(chunkIds.get(0));
        assertNotNull(chunk);
        // jsonb::text 序列化在冒号后带空格，用键与值分别断言
        assertTrue(chunk.getMetadata().contains("docId") && chunk.getMetadata().contains(TEST_DOC_ID));
        assertTrue(chunk.getMetadata().contains("sourceType") && chunk.getMetadata().contains("paper"));
        assertNotNull(chunk.getEmbedding());
        assertTrue(chunk.getEmbedding().length > 0, "bge-m3 嵌入应为非空向量");
    }

    @Test
    public void importCorpusShouldSkipExistingIdempotently() {
        paperCorpusService.importCorpus(tmpDir.toString(), 2000);

        CorpusImportResponse second = paperCorpusService.importCorpus(tmpDir.toString(), 2000);
        assertEquals(0, second.getProcessed(), "已导入论文应跳过");
        // 真实库中已导入的论文会与测试论文一同计入跳过数，断言下界即可
        assertTrue(second.getSkippedExisting() >= 1);
        assertEquals(0, second.getRemaining());
    }

    private void buildTestPdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String text : new String[]{"LEO satellite network test page one",
                    "Constellation routing latency page two"}) {
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
            doc.save(target.toFile());
        }
    }
}
