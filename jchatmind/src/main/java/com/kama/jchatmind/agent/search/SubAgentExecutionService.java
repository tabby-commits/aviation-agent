package com.kama.jchatmind.agent.search;

import com.kama.jchatmind.agent.search.model.AggregateStats;
import com.kama.jchatmind.agent.search.model.DelegationResult;
import com.kama.jchatmind.agent.search.model.GlobalPolicy;
import com.kama.jchatmind.agent.search.model.SearchDelegationErrorCode;
import com.kama.jchatmind.agent.search.model.SearchPolicy;
import com.kama.jchatmind.agent.search.model.SubTaskFailure;
import com.kama.jchatmind.agent.search.model.SubTaskResult;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import com.kama.jchatmind.agent.search.model.TokenUsage;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.service.SseService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class SubAgentExecutionService {

    private final SubAgentRuntimeFactory runtimeFactory;
    private final AgenticSearchProperties properties;
    private final SearchService searchService;
    private final SseService sseService;
    private final Executor executor;

    public SubAgentExecutionService(SubAgentRuntimeFactory runtimeFactory,
                                    AgenticSearchProperties properties,
                                    SearchService searchService,
                                    SseService sseService,
                                    @Qualifier("subAgentTaskExecutor") Executor executor) {
        this.runtimeFactory = runtimeFactory;
        this.properties = properties;
        this.searchService = searchService;
        this.sseService = sseService;
        this.executor = executor;
    }

    public DelegationResult execute(List<SubTaskSpec> tasks,
                                    GlobalPolicy policy,
                                    String parentSessionId,
                                    String model) {
        long started = System.currentTimeMillis();
        if (tasks == null || tasks.isEmpty()) {
            return new DelegationResult(List.of(), List.of(new SubTaskFailure(
                    "global",
                    SearchDelegationErrorCode.SEARCH_DELEGATION_INVALID_INPUT.name(),
                    "tasks 不能为空"
            )), new AggregateStats(0, new TokenUsage(0, 0)));
        }

        emit(parentSessionId, SseMessage.Type.AGENTIC_DELEGATING, "delegating", null, null, tasks.size(), null);

        Semaphore semaphore = new Semaphore(resolveMaxParallel(policy));
        int globalTimeout = resolveGlobalTimeout(policy);
        List<CompletableFuture<TaskOutcome>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> runWithPermit(semaphore, task, parentSessionId, model), executor))
                .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(globalTimeout, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // collect completed tasks below; unfinished futures become timeout failures
        }

        List<SubTaskResult> results = new ArrayList<>();
        List<SubTaskFailure> failures = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<TaskOutcome> future = futures.get(i);
            SubTaskSpec spec = tasks.get(i);
            if (!future.isDone()) {
                future.cancel(true);
                failures.add(timeout(spec.taskId()));
                continue;
            }
            TaskOutcome outcome = future.getNow(null);
            if (outcome == null) {
                failures.add(timeout(spec.taskId()));
            } else if (outcome.result() != null) {
                results.add(outcome.result());
            } else {
                failures.add(outcome.failure());
            }
        }

        emit(parentSessionId, SseMessage.Type.AGENTIC_DONE, "done", null, null, tasks.size(), null);
        return new DelegationResult(
                results,
                failures,
                new AggregateStats(System.currentTimeMillis() - started, new TokenUsage(0, 0))
        );
    }

    private TaskOutcome runWithPermit(Semaphore semaphore, SubTaskSpec spec, String parentSessionId, String model) {
        try {
            semaphore.acquire();
            return runOne(spec, parentSessionId, model);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TaskOutcome(null, timeout(spec.taskId()));
        } finally {
            semaphore.release();
        }
    }

    private TaskOutcome runOne(SubTaskSpec spec, String parentSessionId, String model) {
        try {
            validate(spec);
            FallbackDecision fallback = applyFallback(spec, parentSessionId);
            emit(parentSessionId, SseMessage.Type.AGENTIC_SUBAGENT_PROGRESS, "running", 1, maxSteps(fallback.spec()), null, spec.taskId());
            SubTaskResult result = runtimeFactory.runSubAgent(
                    fallback.spec(),
                    parentSessionId,
                    model,
                    maxSteps(fallback.spec()),
                    fallback.fallback()
            );
            return new TaskOutcome(result, null);
        } catch (Exception e) {
            String code = classify(e);
            return new TaskOutcome(null, new SubTaskFailure(spec.taskId(), code,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            emit(parentSessionId, SseMessage.Type.AGENTIC_SUBAGENT_PROGRESS, "finished", 1, 1, null, spec.taskId());
        }
    }

    private FallbackDecision applyFallback(SubTaskSpec spec, String parentSessionId) {
        SearchPolicy policy = spec.searchPolicy();
        if (policy != null && policy.allowWebSearch() && policy.allowKbSearch() && !searchService.isAvailable()) {
            SearchPolicy fallbackPolicy = new SearchPolicy(
                    true,
                    false,
                    policy.maxSubSteps(),
                    policy.timeoutSeconds()
            );
            emit(parentSessionId, SseMessage.Type.AGENTIC_FALLBACK, "web_to_kb", null, null, null, spec.taskId());
            return new FallbackDecision(new SubTaskSpec(
                    spec.taskId(),
                    spec.taskDescription(),
                    spec.kbId(),
                    spec.scope(),
                    fallbackPolicy
            ), "web_to_kb");
        }
        return new FallbackDecision(spec, null);
    }

    private void validate(SubTaskSpec spec) {
        if (spec == null || !StringUtils.hasText(spec.taskId()) || !StringUtils.hasText(spec.taskDescription())) {
            throw new IllegalArgumentException("taskId 和 taskDescription 不能为空");
        }
        SearchPolicy policy = spec.searchPolicy();
        if (policy == null || (!policy.allowKbSearch() && !policy.allowWebSearch())) {
            throw new IllegalArgumentException("子任务至少需要启用一种检索来源");
        }
        if (policy.allowKbSearch() && !StringUtils.hasText(spec.kbId())) {
            throw new IllegalArgumentException("启用知识库检索时 kbId 不能为空");
        }
    }

    private int resolveMaxParallel(GlobalPolicy policy) {
        int requested = policy == null || policy.maxParallel() <= 0
                ? properties.getDelegation().getMaxParallel()
                : policy.maxParallel();
        return Math.max(1, Math.min(requested, properties.getDelegation().getMaxParallel()));
    }

    private int resolveGlobalTimeout(GlobalPolicy policy) {
        return policy == null || policy.globalTimeoutSeconds() <= 0
                ? properties.getDelegation().getGlobalTimeoutSeconds()
                : policy.globalTimeoutSeconds();
    }

    private int maxSteps(SubTaskSpec spec) {
        int requested = spec.searchPolicy() == null || spec.searchPolicy().maxSubSteps() <= 0
                ? properties.getDelegation().getMaxSubStepsCap()
                : spec.searchPolicy().maxSubSteps();
        return Math.max(1, Math.min(requested, properties.getDelegation().getMaxSubStepsCap()));
    }

    private String classify(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return SearchDelegationErrorCode.SEARCH_DELEGATION_INVALID_INPUT.name();
        }
        return SearchDelegationErrorCode.SEARCH_DELEGATION_PROVIDER_ERROR.name();
    }

    private SubTaskFailure timeout(String taskId) {
        return new SubTaskFailure(
                taskId,
                SearchDelegationErrorCode.SEARCH_DELEGATION_TIMEOUT.name(),
                "子 Agent 执行超时"
        );
    }

    private void emit(String sessionId,
                      SseMessage.Type type,
                      String stage,
                      Integer step,
                      Integer totalSteps,
                      Integer total,
                      String taskId) {
        if (!StringUtils.hasText(sessionId) || sseService == null) {
            return;
        }
        try {
            sseService.send(sessionId, SseMessage.builder()
                    .type(type)
                    .payload(SseMessage.Payload.builder()
                            .stage(stage)
                            .step(step)
                            .totalSteps(totalSteps)
                            .total(total)
                            .taskId(taskId)
                            .build())
                    .build());
        } catch (Exception ignored) {
            // SSE is best-effort here; missing browser connection must not fail the agent.
        }
    }

    private record TaskOutcome(SubTaskResult result, SubTaskFailure failure) {
    }

    private record FallbackDecision(SubTaskSpec spec, String fallback) {
    }
}
