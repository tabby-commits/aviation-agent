package com.kama.jchatmind.agent.search;

import com.kama.jchatmind.agent.search.model.AggregateStats;
import com.kama.jchatmind.agent.search.model.DelegationResult;
import com.kama.jchatmind.agent.search.model.GlobalPolicy;
import com.kama.jchatmind.agent.search.model.SearchPolicy;
import com.kama.jchatmind.agent.search.model.SubTaskResult;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import com.kama.jchatmind.config.AgenticSearchProperties;
import com.kama.jchatmind.search.SearchService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubAgentExecutionServiceTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final SubAgentRuntimeFactory runtimeFactory = mock(SubAgentRuntimeFactory.class);
    private final SearchService searchService = mock(SearchService.class);
    private final SseService sseService = mock(SseService.class);
    private final AgenticSearchProperties properties = new AgenticSearchProperties();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void aggregatesParallelResults() {
        when(searchService.isAvailable()).thenReturn(true);
        when(runtimeFactory.runSubAgent(any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> ok(invocation.getArgument(0, SubTaskSpec.class).taskId()));
        SubAgentExecutionService service = service();

        DelegationResult result = service.execute(List.of(task("t1"), task("t2")),
                new GlobalPolicy(2, 5), "session-1", "deepseek");

        assertThat(result.results()).extracting(SubTaskResult::taskId).containsExactlyInAnyOrder("t1", "t2");
        assertThat(result.failures()).isEmpty();
        assertThat(result.aggregate()).isInstanceOf(AggregateStats.class);
    }

    @Test
    void invalidInputBecomesFailure() {
        when(searchService.isAvailable()).thenReturn(true);
        SubAgentExecutionService service = service();

        DelegationResult result = service.execute(List.of(new SubTaskSpec("", "", null, null, null)),
                new GlobalPolicy(1, 5), null, null);

        assertThat(result.results()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).errorCode()).isEqualTo("SEARCH_DELEGATION_INVALID_INPUT");
    }

    @Test
    void blankKbIdWithWebSearchFallsBackToWebOnly() {
        when(searchService.isAvailable()).thenReturn(true);
        when(runtimeFactory.runSubAgent(any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> ok(invocation.getArgument(0, SubTaskSpec.class).taskId()));
        SubAgentExecutionService service = service();
        SubTaskSpec task = new SubTaskSpec("web-task", "search task", "", null,
                new SearchPolicy(true, true, 3, 5));

        DelegationResult result = service.execute(List.of(task), new GlobalPolicy(1, 5), null, null);

        assertThat(result.results()).extracting(SubTaskResult::taskId).containsExactly("web-task");
        assertThat(result.failures()).isEmpty();
        ArgumentCaptor<SubTaskSpec> specCaptor = ArgumentCaptor.forClass(SubTaskSpec.class);
        verify(runtimeFactory).runSubAgent(specCaptor.capture(), any(), any(), anyInt(), any());
        SubTaskSpec normalized = specCaptor.getValue();
        assertThat(normalized.kbId()).isNull();
        assertThat(normalized.searchPolicy().allowKbSearch()).isFalse();
        assertThat(normalized.searchPolicy().allowWebSearch()).isTrue();
    }

    @Test
    void globalTimeoutProducesTimeoutFailure() {
        when(searchService.isAvailable()).thenReturn(true);
        when(runtimeFactory.runSubAgent(any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(2_000);
                    return ok("slow");
                });
        SubAgentExecutionService service = service();

        DelegationResult result = service.execute(List.of(task("slow")),
                new GlobalPolicy(1, 1), null, null);

        assertThat(result.results()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).errorCode()).isEqualTo("SEARCH_DELEGATION_TIMEOUT");
    }

    private SubAgentExecutionService service() {
        return new SubAgentExecutionService(runtimeFactory, properties, searchService, sseService, executor);
    }

    private SubTaskSpec task(String taskId) {
        return new SubTaskSpec(taskId, "search task", "kb1", null, new SearchPolicy(true, false, 3, 5));
    }

    private SubTaskResult ok(String taskId) {
        return new SubTaskResult(taskId, "OK", "summary", List.of(), List.of(), null);
    }
}
