package com.kama.jchatmind.evaluation;

import com.kama.jchatmind.evaluation.model.request.CreateEvaluationRunRequest;
import com.kama.jchatmind.evaluation.model.request.UpdateCheckItemsRequest;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunDetailResponse;
import com.kama.jchatmind.evaluation.service.EvaluationRunFacadeService;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.EvaluationCheckItemMapper;
import com.kama.jchatmind.mapper.EvaluationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行记录与客观检查集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class EvaluationRunServiceTest {

    @Autowired
    private EvaluationRunFacadeService evaluationRunFacadeService;

    @Autowired
    private EvaluationRunMapper evaluationRunMapper;

    @Autowired
    private EvaluationCheckItemMapper evaluationCheckItemMapper;

    private String createdRunId;

    @AfterEach
    public void cleanup() {
        if (createdRunId != null) {
            evaluationCheckItemMapper.deleteByRunId(createdRunId);
            evaluationRunMapper.updateReport(createdRunId, null); // 占位，无 delete mapper 则残留无害
            createdRunId = null;
        }
    }

    @Test
    public void createRunShouldGenerateEightPendingChecks() {
        CreateEvaluationRunRequest request = new CreateEvaluationRunRequest();
        request.setChatSessionId("test-session");
        request.setQuestion("中美低轨卫星星座网络研究布局比较");
        request.setReport("（报告待生成）");

        EvaluationRunDetailResponse detail = evaluationRunFacadeService.createRun(request);
        createdRunId = detail.getId();
        assertNotNull(detail.getId());
        assertEquals(8, detail.getChecks().size());
        assertTrue(detail.getChecks().stream().allMatch(c -> "pending".equals(c.status())));
        assertTrue(detail.getChecks().stream()
                .anyMatch(c -> "citation_completeness".equals(c.itemKey()) && c.title().contains("引用")));

        // 列表可见
        assertTrue(evaluationRunFacadeService.listRuns(10).stream()
                .anyMatch(s -> detail.getId().equals(s.getId())));
    }

    @Test
    public void updateChecksShouldPersistAndValidate() {
        CreateEvaluationRunRequest request = new CreateEvaluationRunRequest();
        request.setQuestion("测试问题");
        EvaluationRunDetailResponse detail = evaluationRunFacadeService.createRun(request);
        createdRunId = detail.getId();

        UpdateCheckItemsRequest update = new UpdateCheckItemsRequest();
        UpdateCheckItemsRequest.CheckItemUpdate item = new UpdateCheckItemsRequest.CheckItemUpdate();
        item.setItemKey("citation_completeness");
        item.setStatus("符合");
        item.setNote("全部结论带 docId 引用");
        update.setItems(List.of(item));

        evaluationRunFacadeService.updateChecks(detail.getId(), update);

        EvaluationRunDetailResponse reloaded = evaluationRunFacadeService.getRun(detail.getId());
        assertEquals("符合", reloaded.getChecks().stream()
                .filter(c -> "citation_completeness".equals(c.itemKey()))
                .findFirst().orElseThrow().status());

        // 非法状态应拒绝
        UpdateCheckItemsRequest bad = new UpdateCheckItemsRequest();
        UpdateCheckItemsRequest.CheckItemUpdate badItem = new UpdateCheckItemsRequest.CheckItemUpdate();
        badItem.setItemKey("citation_completeness");
        badItem.setStatus("很好");
        bad.setItems(List.of(badItem));
        assertThrows(BizException.class, () -> evaluationRunFacadeService.updateChecks(detail.getId(), bad));

        // 非法 item_key 应拒绝
        UpdateCheckItemsRequest badKey = new UpdateCheckItemsRequest();
        UpdateCheckItemsRequest.CheckItemUpdate badKeyItem = new UpdateCheckItemsRequest.CheckItemUpdate();
        badKeyItem.setItemKey("not_a_check");
        badKeyItem.setStatus("符合");
        badKey.setItems(List.of(badKeyItem));
        assertThrows(BizException.class, () -> evaluationRunFacadeService.updateChecks(detail.getId(), badKey));
    }

    @Test
    public void emptyQuestionShouldBeRejected() {
        CreateEvaluationRunRequest request = new CreateEvaluationRunRequest();
        request.setQuestion(" ");
        assertThrows(BizException.class, () -> evaluationRunFacadeService.createRun(request));
    }
}
