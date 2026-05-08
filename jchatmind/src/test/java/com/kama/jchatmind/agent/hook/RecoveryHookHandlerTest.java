package com.kama.jchatmind.agent.hook;

import com.kama.jchatmind.agent.AgentRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryHookHandlerTest {

    private final RecoveryHookHandler handler = new RecoveryHookHandler();

    @Test
    void emptyToolResultRequestsSingleRetryWithinBudget() {
        AgentHookContext context = AgentHookContext.builder()
                .sessionId("session-1")
                .agentRole(AgentRole.MAIN)
                .stepIndex(1)
                .toolName("KnowledgeTool")
                .toolArguments("{\"query\":\"x\"}")
                .rawResult("")
                .retryCount(0)
                .recoveryBudget(RecoveryBudget.defaults())
                .build();

        RecoveryDecision decision = handler.onToolEmptyResult(context);

        assertThat(decision.action()).isEqualTo(RecoveryAction.RETRY_TOOL);
        assertThat(decision.observation()).contains("KnowledgeTool").contains("empty");
    }

    @Test
    void repeatedToolFailureContinuesWithStructuredObservation() {
        AgentHookContext context = AgentHookContext.builder()
                .sessionId("session-1")
                .agentRole(AgentRole.MAIN)
                .stepIndex(2)
                .toolName("KnowledgeTool")
                .error(new IllegalStateException("provider unavailable"))
                .retryCount(1)
                .recoveryBudget(RecoveryBudget.defaults())
                .build();

        RecoveryDecision decision = handler.onToolError(context);

        assertThat(decision.action()).isEqualTo(RecoveryAction.CONTINUE);
        assertThat(decision.observation()).contains("provider unavailable").contains("exhausted");
    }

    @Test
    void subAgentFailureRequestsSingleRedelegationWithinBudget() {
        AgentHookContext context = AgentHookContext.builder()
                .sessionId("session-1")
                .agentRole(AgentRole.MAIN)
                .stepIndex(1)
                .taskId("task-1")
                .error(new RuntimeException("bad json"))
                .retryCount(0)
                .recoveryBudget(RecoveryBudget.defaults())
                .build();

        RecoveryDecision decision = handler.onSubAgentFailure(context);

        assertThat(decision.action()).isEqualTo(RecoveryAction.REDELEGATE_SUBTASK);
        assertThat(decision.observation()).contains("task-1").contains("redelegate");
    }
}
