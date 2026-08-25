package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.PaperMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 论文元数据接口集成测试（MockMvc）
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
@AutoConfigureMockMvc
public class PaperControllerTest {

    private static final String WOS_CSV =
            "PT,AU,TI,SO,DT,PY,AB,TC,C1,UT,DI\n"
            + "C,\"A, B\",Controller Paper,J1,Article,2023,Abs,1,\"Inst, CN\",WOS:TEST-CTRL1,\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaperMapper paperMapper;

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
    }

    @Test
    public void importThenQueryThenDetailThenStats() throws Exception {
        // 1. 导入
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", WOS_CSV.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/papers/import/metadata")
                        .file(file)
                        .param("source", "WOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.inserted").value(1));

        // 2. 关键词分页查询
        MvcResult listResult = mockMvc.perform(get("/api/papers")
                        .param("keyword", "Controller Paper")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();

        JsonNode papers = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .at("/data/papers");
        assertEquals(1, papers.size());
        assertEquals("WOS:TEST-CTRL1", papers.get(0).get("docId").asText());
        assertEquals("Controller Paper", papers.get(0).get("title").asText());

        // 3. 详情
        mockMvc.perform(get("/api/papers/WOS:TEST-CTRL1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docId").value("WOS:TEST-CTRL1"))
                .andExpect(jsonPath("$.data.firstAuthor").value("A, B"));

        // 4. 统计
        mockMvc.perform(get("/api/papers/import/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bySourceDb.WOS").isNotEmpty());
    }

    @Test
    public void detailOfAbsentPaperShouldReturnError() throws Exception {
        mockMvc.perform(get("/api/papers/WOS:TEST-NOT-EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
