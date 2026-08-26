package com.kama.jchatmind.evaluation.service.impl;

import com.kama.jchatmind.evaluation.model.request.CreateEvaluationRunRequest;
import com.kama.jchatmind.evaluation.model.request.UpdateCheckItemsRequest;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunDetailResponse;
import com.kama.jchatmind.evaluation.model.response.EvaluationRunSummaryResponse;
import com.kama.jchatmind.evaluation.service.EvaluationRunFacadeService;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.EvaluationCheckItemMapper;
import com.kama.jchatmind.mapper.EvaluationRunMapper;
import com.kama.jchatmind.model.entity.EvaluationCheckItem;
import com.kama.jchatmind.model.entity.EvaluationRun;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运行记录与客观检查实现
 */
@Service
@AllArgsConstructor
public class EvaluationRunFacadeServiceImpl implements EvaluationRunFacadeService {

    private static final Set<String> VALID_STATUS = Set.of("符合", "不符合", "证据不足", "pending");

    private final EvaluationRunMapper evaluationRunMapper;

    private final EvaluationCheckItemMapper evaluationCheckItemMapper;

    @Override
    public EvaluationRunDetailResponse createRun(CreateEvaluationRunRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new BizException("问题说明不能为空");
        }
        EvaluationRun run = EvaluationRun.builder()
                .chatSessionId(request.getChatSessionId())
                .question(request.getQuestion())
                .report(request.getReport())
                .agentConfig(request.getAgentConfig())
                .build();
        evaluationRunMapper.insert(run);

        // 生成八项客观检查项（pending）
        for (String[] item : CHECK_ITEMS) {
            evaluationCheckItemMapper.upsert(EvaluationCheckItem.builder()
                    .runId(run.getId())
                    .itemKey(item[0])
                    .status("pending")
                    .build());
        }
        return getRun(run.getId());
    }

    @Override
    public EvaluationRunDetailResponse getRun(String runId) {
        EvaluationRun run = evaluationRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行记录不存在：" + runId);
        }
        return toDetail(run);
    }

    @Override
    public List<EvaluationRunSummaryResponse> listRuns(int limit) {
        return evaluationRunMapper.selectAll(Math.min(Math.max(limit, 1), 100)).stream()
                .map(run -> {
                    long concluded = evaluationCheckItemMapper.selectByRunId(run.getId()).stream()
                            .filter(c -> !"pending".equals(c.getStatus()))
                            .count();
                    return EvaluationRunSummaryResponse.builder()
                            .id(run.getId())
                            .chatSessionId(run.getChatSessionId())
                            .question(run.getQuestion())
                            .createdAt(run.getCreatedAt())
                            .checkProgress(concluded + "/" + CHECK_ITEMS.size())
                            .build();
                })
                .toList();
    }

    @Override
    public EvaluationRunDetailResponse updateReport(String runId, String report) {
        EvaluationRun run = evaluationRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行记录不存在：" + runId);
        }
        evaluationRunMapper.updateReport(runId, report);
        return getRun(runId);
    }

    @Override
    public List<EvaluationCheckItemView> updateChecks(String runId, UpdateCheckItemsRequest request) {
        EvaluationRun run = evaluationRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行记录不存在：" + runId);
        }
        Set<String> validKeys = CHECK_ITEMS.stream().map(i -> i[0]).collect(Collectors.toSet());
        for (UpdateCheckItemsRequest.CheckItemUpdate item : request.getItems()) {
            if (!validKeys.contains(item.getItemKey())) {
                throw new BizException("非法检查项：" + item.getItemKey());
            }
            if (!VALID_STATUS.contains(item.getStatus())) {
                throw new BizException("非法检查状态：" + item.getStatus() + "（符合/不符合/证据不足）");
            }
            evaluationCheckItemMapper.upsert(EvaluationCheckItem.builder()
                    .runId(runId)
                    .itemKey(item.getItemKey())
                    .status(item.getStatus())
                    .note(item.getNote())
                    .build());
        }
        return getRun(runId).getChecks();
    }

    private EvaluationRunDetailResponse toDetail(EvaluationRun run) {
        Map<String, EvaluationCheckItem> byKey = evaluationCheckItemMapper.selectByRunId(run.getId()).stream()
                .collect(Collectors.toMap(EvaluationCheckItem::getItemKey, Function.identity()));
        List<EvaluationCheckItemView> checks = new ArrayList<>();
        for (String[] item : CHECK_ITEMS) {
            EvaluationCheckItem saved = byKey.get(item[0]);
            checks.add(new EvaluationCheckItemView(
                    item[0], item[1],
                    saved == null ? "pending" : saved.getStatus(),
                    saved == null ? null : saved.getNote()));
        }
        return EvaluationRunDetailResponse.builder()
                .id(run.getId())
                .chatSessionId(run.getChatSessionId())
                .question(run.getQuestion())
                .report(run.getReport())
                .agentConfig(run.getAgentConfig())
                .createdAt(run.getCreatedAt())
                .checks(checks)
                .build();
    }
}
