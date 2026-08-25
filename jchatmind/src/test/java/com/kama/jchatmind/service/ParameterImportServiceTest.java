package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.mapper.ParameterEvidenceMapper;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.entity.ParameterEvidence;
import com.kama.jchatmind.model.request.ParameterQueryRequest;
import com.kama.jchatmind.model.response.GetParametersResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 参数证据导入与查询集成测试
 * 样例取自真实 confirmed_parameters_final.csv 首行的精简版
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class ParameterImportServiceTest {

    /** 覆盖：正常行、数值解析失败容错行、缺 decision_id 行 */
    private static final String PARAMS_CSV =
            "decision_id,doc_id,technical_object,parameter_name_raw,parameter_name_canonical,"
            + "parameter_family,value_raw,comparator,value_min,value_max,unit_raw,unit_normalized,"
            + "condition_text,evidence_text,page_number,section,source_type,leaf_path,result_form,"
            + "review_status,first_author_country,title\n"
            + "d_TEST-0001,WOS:TEST-PM1,FedLEO,Accuracy (%) / MNIST,accuracy,"
            + "classification and prediction quality,89.37,,89.37,89.37,%,%,"
            + "MNIST,FL Approaches: FedLEO | Accuracy (%) / MNIST: 89.37,5,METHODS,table,"
            + "LEO Tree > Space Computing,absolute,reviewed_rule,US,Optimizing Federated Learning\n"
            + "d_TEST-0002,WOS:TEST-PM1,某系统,时延,latency,"
            + "时延/延迟,20ms,,not-a-number,20.0,ms,ms,"
            + "城市环境,实验测得端到端时延 20ms,8,RESULTS,text,"
            + "LEO Tree > Networking,absolute,reviewed_rule,CN,某中文论文\n"
            + ",WOS:TEST-PM1,无决策ID行,x,x,x,x,,1,1,u,u,c,e,1,s,t,l,f,r,CN,t\n";

    @Autowired
    private ParameterFacadeService parameterFacadeService;

    @Autowired
    private PaperMapper paperMapper;

    @Autowired
    private ParameterEvidenceMapper parameterEvidenceMapper;

    @BeforeEach
    public void setup() {
        parameterEvidenceMapper.deleteByDecisionIdPrefix("d_TEST-");
        paperMapper.deleteByDocIdPrefix("WOS:TEST-PM");
        paperMapper.upsert(Paper.builder().docId("WOS:TEST-PM1").sourceDb("WOS")
                .title("Optimizing Federated Learning").publishYear(2023)
                .screeningStatus("pending").build());
    }

    @AfterEach
    public void cleanup() {
        parameterEvidenceMapper.deleteByDecisionIdPrefix("d_TEST-");
        paperMapper.deleteByDocIdPrefix("WOS:TEST-PM");
    }

    @Test
    public void importParametersShouldUpsertAndEnrichYear() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "params.csv", "text/csv", PARAMS_CSV.getBytes(StandardCharsets.UTF_8));

        PaperImportResponse first = parameterFacadeService.importParameters(file);
        // 第 3 行缺 decision_id 被解析器过滤，共 2 条
        assertEquals(2, first.getTotal());
        assertEquals(2, first.getInserted());
        assertEquals(0, first.getFailed());

        ParameterEvidence saved = parameterEvidenceMapper.selectByDecisionId("d_TEST-0001");
        assertNotNull(saved);
        assertEquals("WOS:TEST-PM1", saved.getDocId());
        assertEquals("accuracy", saved.getParameterNameCanonical());
        assertEquals(89.37, saved.getValueMin());
        assertEquals("classification and prediction quality", saved.getParameterFamily());
        assertEquals(5, saved.getPageNumber());
        assertEquals("table", saved.getSourceType());
        // publish_year 从 paper 表富化
        assertEquals(2023, saved.getPublishYear());

        // 数值解析失败容错为 null
        ParameterEvidence latency = parameterEvidenceMapper.selectByDecisionId("d_TEST-0002");
        assertNotNull(latency);
        assertEquals(null, latency.getValueMin());
        assertEquals(20.0, latency.getValueMax());

        // 幂等：重导全走更新
        PaperImportResponse second = parameterFacadeService.importParameters(file);
        assertEquals(0, second.getInserted());
        assertEquals(2, second.getUpdated());
    }

    @Test
    public void queryParametersShouldFilterByFamilyAndCountry() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "params.csv", "text/csv", PARAMS_CSV.getBytes(StandardCharsets.UTF_8));
        parameterFacadeService.importParameters(file);

        ParameterQueryRequest byFamily = new ParameterQueryRequest();
        byFamily.setFamily("时延/延迟");
        GetParametersResponse familyResp = parameterFacadeService.getParameters(byFamily);
        assertEquals(1, familyResp.getTotal());

        ParameterQueryRequest byCountry = new ParameterQueryRequest();
        byCountry.setCountry("CN");
        assertEquals(1, parameterFacadeService.getParameters(byCountry).getTotal());

        ParameterQueryRequest byKeyword = new ParameterQueryRequest();
        byKeyword.setKeyword("FedLEO");
        assertEquals(1, parameterFacadeService.getParameters(byKeyword).getTotal());
    }
}
